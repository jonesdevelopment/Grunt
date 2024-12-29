package net.spartanb312.grunt.process.transformers.encrypt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.spartanb312.genesis.kotlin.clinit
import net.spartanb312.genesis.kotlin.extensions.*
import net.spartanb312.genesis.kotlin.extensions.insn.*
import net.spartanb312.genesis.kotlin.field
import net.spartanb312.genesis.kotlin.instructions
import net.spartanb312.genesis.kotlin.method
import net.spartanb312.grunt.config.Configs
import net.spartanb312.grunt.config.setting
import net.spartanb312.grunt.process.MethodProcessor
import net.spartanb312.grunt.process.Transformer
import net.spartanb312.grunt.process.resource.ResourceCache
import net.spartanb312.grunt.process.transformers.rename.ReflectionSupportTransformer
import net.spartanb312.grunt.utils.count
import net.spartanb312.grunt.utils.extensions.isInterface
import net.spartanb312.grunt.utils.getRandomString
import net.spartanb312.grunt.utils.logging.Logger
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import kotlin.random.Random

/**
 * Encrypt strings
 * Last update on 2024/12/28
 */
object StringEncryptTransformer : Transformer("StringEncrypt", Category.Encryption), MethodProcessor {

    private val arrayed by setting("Arrayed", false)
    private val replaceInvokeDynamics by setting("ReplaceInvokeDynamics", true)
    private val exclusion by setting("Exclusion", listOf())

    private val String.reflectionExcluded
        get() = ReflectionSupportTransformer.enabled && ReflectionSupportTransformer.strBlacklist.contains(this)

    override fun ResourceCache.transform() {
        Logger.info(" - Encrypting strings...")
        val count = count {
            runBlocking {
                nonExcluded.asSequence()
                    .filter { c -> c.version > Opcodes.V1_5 && exclusion.none { c.name.startsWith(it) }
                    }.forEach { classNode ->
                        fun job() {
                            transformClass(classNode, null)
                        }
                        if (Configs.Settings.parallel) launch(Dispatchers.Default) { job() } else job()
                    }
            }
        }.get()
        Logger.info("    Encrypted $count strings")
    }

    override fun transformMethod(classNode: ClassNode, methodNode: MethodNode) {
        transformClass(classNode, methodNode)
    }

    private fun transformClass(classNode: ClassNode, onlyObfuscate: MethodNode?) {
        val stringsToEncrypt = mutableMapOf<String, Int>()
        val classKey = Random.nextInt()

        // First, replace all INVOKEDYNAMIC instructions with LDC instructions.
        if (replaceInvokeDynamics) {
            replaceInvokeDynamics(classNode, onlyObfuscate)
        }

        // Then, go over all LDC instructions and collect them.
        classNode.methods.shuffled().forEach { methodNode ->
            if (onlyObfuscate != null && onlyObfuscate != methodNode) return@forEach
            methodNode.instructions.asSequence()
                .filter { it is LdcInsnNode && it.cst is String }
                .shuffled()
                .forEach { instruction ->
                    val originalString = (instruction as LdcInsnNode).cst as String
                    // Skip duplicate strings
                    val existingIndex = stringsToEncrypt[originalString]
                    stringsToEncrypt.putIfAbsent(originalString, existingIndex ?: stringsToEncrypt.size)
                }
        }

        if (stringsToEncrypt.isNotEmpty()) {
            val poolField = field(
                (if (classNode.isInterface) PUBLIC else PRIVATE) + STATIC,
                getRandomString(16),
                "[Ljava/lang/String;",
                null, null)
            val decryptMethod = createDecryptMethod(classNode, getRandomString(16), classKey)
            val encryptedStrings = stringsToEncrypt.keys.map { it }.toTypedArray()
            val arrayInitMethod = method(Opcodes.ACC_STATIC, getRandomString(16), "()V") {
                INSTRUCTIONS {
                    INT(encryptedStrings.size)
                    ANEWARRAY("java/lang/String")
                    encryptedStrings.forEachIndexed { index, string ->
                        val key = (Random.nextInt() and 0xFF) + 1
                        val seed = Random.nextLong(100000L)
                        val encrypted = encrypt(string.toCharArray(), seed, key, classKey)
                        DUP
                        INT(index)
                        if (arrayed) {
                            INT(encrypted.length)
                            NEWARRAY(Opcodes.T_CHAR)
                            for (i in 0..(encrypted.length - 1)) {
                                DUP
                                INT(i)
                                INT(encrypted[i].code)
                                CASTORE
                            }
                        } else {
                            LDC(encrypted)
                            INVOKEVIRTUAL("java/lang/String", "toCharArray", "()[C", false)
                        }
                        LONG(seed)
                        INT(key)
                        INVOKESTATIC(classNode.name, decryptMethod.name, decryptMethod.desc, false)
                        AASTORE
                    }
                    PUTSTATIC(classNode.name, poolField.name, poolField.desc)
                    RETURN
                }
                MAXS(3, 0)
            }

            (classNode.methods.find { it.name == "<clinit>" } ?: clinit().also {
                it.instructions.insert(InsnNode(Opcodes.RETURN))
                classNode.methods.add(it)
            }).instructions.insert(instructions {
                INVOKESTATIC(classNode.name, arrayInitMethod.name, arrayInitMethod.desc)
            })

            classNode.methods.forEach { methodNode ->
                if (onlyObfuscate != null && onlyObfuscate != methodNode) return@forEach
                methodNode.instructions.asSequence()
                    .filter { it is LdcInsnNode && it.cst is String }
                    .shuffled()
                    .forEach { instruction ->
                        val originalString = (instruction as LdcInsnNode).cst as String
                        val index = stringsToEncrypt[originalString]!!
                        methodNode.instructions.insert(instruction, instructions {
                            GETSTATIC(classNode.name, poolField.name, poolField.desc)
                            INT(index)
                            AALOAD
                        })
                        methodNode.instructions.remove(instruction)
                    }
            }

            classNode.fields.add(poolField)
            classNode.methods.add(decryptMethod)
            classNode.methods.add(arrayInitMethod)
        }
    }

    // https://github.com/yaskylan/GotoObfuscator/blob/master/src/main/java/org/g0to/transformer/features/stringencryption/
    fun replaceInvokeDynamics(classNode: ClassNode, onlyObfuscate: MethodNode?) {
        val invokeDynamicConcatMethods = ArrayList<MethodNode>()

        classNode.methods.forEach { methodNode ->
            if (onlyObfuscate != null && onlyObfuscate != methodNode) return@forEach
            methodNode.instructions.asSequence()
                .filter { it is InvokeDynamicInsnNode && isStringConcatenation(it) }
                .shuffled()
                .forEach { instruction ->
                    invokeDynamicConcatMethods.add(processStringConcatenation(
                        classNode,
                        methodNode,
                        instruction as InvokeDynamicInsnNode,
                        getRandomString(16)
                    ))
                }
        }

        invokeDynamicConcatMethods.forEach {
            classNode.methods.add(it)
        }
    }

    fun isStringConcatenation(instruction: InvokeDynamicInsnNode): Boolean {
        return instruction.name.equals("makeConcatWithConstants")
                && instruction.bsmArgs[0].toString().find { it != '\u0001' } != null
    }

    fun processStringConcatenation(classNode: ClassNode, methodNode: MethodNode,
                                   instruction: InvokeDynamicInsnNode, bootstrapName: String): MethodNode {
        val arg = instruction.bsmArgs[0].toString()
        val argString = StringBuilder()
        val newArg = StringBuilder()
        val constants = ArrayList<String>()

        fun flushArgs() {
            if (argString.isNotEmpty()) {
                constants.add(argString.toString())
                argString.setLength(0)
                newArg.append('\u0002')
            }
        }

        var bsmArgIndex = 1

        for (c in arg) {
            when (c) {
                '\u0001' -> {
                    flushArgs()
                    newArg.append('\u0001')
                }
                '\u0002' -> {
                    flushArgs()
                    constants.add(instruction.bsmArgs[bsmArgIndex++].toString())
                    newArg.append('\u0002')
                }
                else -> {
                    argString.append(c)
                }
            }
        }

        flushArgs()

        if (constants.isEmpty()) {
            throw IllegalStateException()
        }

        val bootstrap = createConcatBootstrap(bootstrapName, constants)

        methodNode.instructions.insert(instruction, instructions {
            INVOKEDYNAMIC(
                instruction.name,
                instruction.desc,
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    classNode.name,
                    bootstrap.name,
                    bootstrap.desc,
                    classNode.isInterface
                ),
                newArg.toString()
            )
        })
        methodNode.instructions.remove(instruction)
        return bootstrap
    }

    private fun createConcatBootstrap(name: String, constants: ArrayList<String>): MethodNode {
        val boostrapMethod = method(
            PUBLIC + STATIC,
            name,
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;") {}

        boostrapMethod.instructions.insert(instructions {
            val callerVar = boostrapMethod.maxLocals++
            val nameVar = boostrapMethod.maxLocals++
            val typeVar = boostrapMethod.maxLocals++
            val argVar = boostrapMethod.maxLocals++

            ALOAD(callerVar)
            ALOAD(nameVar)
            ALOAD(typeVar)
            ALOAD(argVar)
            INT(constants.size)
            ANEWARRAY("java/lang/Object")
            DUP

            for ((i, cst) in constants.withIndex()) {
                INT(i)
                LDC(cst)
                AASTORE
                if (i != constants.lastIndex) {
                    DUP
                }
            }

            INVOKESTATIC(
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;")
            ARETURN
        })

        return boostrapMethod
    }

    fun createDecryptMethod(classNode: ClassNode, methodName: String, classKey: Int): MethodNode = method(
        (if (classNode.isInterface) PUBLIC else PRIVATE) + STATIC,
        methodName,
        "([CJI)Ljava/lang/String;"
    ) {
        INSTRUCTIONS {
            LABEL(L["label0"])
            NEW("java/util/Random")
            DUP
            LLOAD(1)
            INVOKESPECIAL("java/util/Random", "<init>", "(J)V")
            ASTORE(4)
            LABEL(L["label1"])
            ALOAD(4)
            INVOKEVIRTUAL("java/util/Random", "nextInt", "()I")
            ILOAD(3)
            INEG
            IXOR
            ISTORE(5)
            LABEL(L["label2"])
            ICONST_0
            ISTORE(6)
            LABEL(L["label3"])
            FRAME(Opcodes.F_APPEND, 3, arrayOf("java/util/Random", Opcodes.INTEGER, Opcodes.INTEGER), 0, null)
            ILOAD(6)
            ALOAD(0)
            ARRAYLENGTH
            IF_ICMPGE(L["label4"])
            LABEL(L["label5"])
            ALOAD(0)
            ILOAD(6)
            ALOAD(0)
            ILOAD(6)
            CALOAD
            ILOAD(5)
            IXOR
            I2C
            CASTORE
            LABEL(L["label6"])
            ILOAD(5)
            ALOAD(4)
            INVOKEVIRTUAL("java/util/Random", "nextInt", "()I")
            ILOAD(3)
            ICONST_M1
            IXOR
            IAND
            IADD
            ISTORE(5)
            LABEL(L["label7"])
            ILOAD(5)
            LDC(classKey)
            IADD
            ISTORE(5)
            LABEL(L["label8"])
            IINC(6, 1)
            GOTO(L["label3"])
            LABEL(L["label4"])
            FRAME(Opcodes.F_CHOP, 1, null, 0, null)
            NEW("java/lang/String")
            DUP
            ALOAD(0)
            INVOKESPECIAL("java/lang/String", "<init>", "([C)V")
            ARETURN
            LABEL(L["label9"])
        }
        MAXS(4, 7)
    }

    fun encrypt(cArray: CharArray, l: Long, n: Int, classKey: Int): String {
        val random = java.util.Random(l)
        var n2 = random.nextInt() xor -n
        for (i in cArray.indices) {
            cArray[i] = (cArray[i].code xor n2).toChar()
            n2 += random.nextInt() and n.inv()
            n2 += classKey
        }
        return String(cArray)
    }
}