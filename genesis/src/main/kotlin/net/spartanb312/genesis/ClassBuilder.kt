package net.spartanb312.genesis

import net.spartanb312.genesis.extensions.*
import org.objectweb.asm.tree.*

@JvmInline
value class ClassBuilder(val classNode: ClassNode) {

    @BuilderDSL
    operator fun FieldNode.unaryPlus() = apply { classNode.fields.add(this) }

    @BuilderDSL
    operator fun MethodNode.unaryPlus() = apply { classNode.methods.add(this) }

    @BuilderDSL
    operator fun InnerClassNode.unaryPlus() = apply { classNode.innerClasses.add(this) }

    @BuilderDSL
    operator fun AnnotationNode.unaryPlus() = apply { classNode.visibleAnnotations.add(this) }

    @BuilderDSL
    operator fun InvisibleAnnotationNode.unaryPlus() = apply { classNode.invisibleAnnotations.add(this) }

}

@BuilderDSL
inline fun ClassBuilder.CLINIT(
    maxStack: Int,
    maxLocals: Int,
    block: InsnListBuilder.(MethodBuilder) -> Unit
): MethodNode {
    require(classNode.methods.none { it.name == "<clinit>" }) {
        "Class ${classNode.name} already has clinit"
    }
    val method = method(
        STATIC,
        "<clinit>",
        "()V", null,
        null
    )
    val insnBuilder = InsnListBuilder(InsnList())
    val methodBuilder = MethodBuilder(method)
    methodBuilder.MAXS(maxStack, maxLocals)
    block.invoke(insnBuilder, methodBuilder)
    +method
    return method
}

@BuilderDSL
fun ClassBuilder.CLINIT(block: MethodBuilder.() -> Unit): MethodNode {
    require(classNode.methods.none { it.name == "<clinit>" }) {
        "Class ${classNode.name} already has clinit"
    }
    val method = method(
        STATIC,
        "<clinit>",
        "()V", null,
        null,
        block
    )
    +method
    return method
}

@NodeDSL
inline fun ClassNode.modify(block: ClassBuilder.() -> Unit): ClassNode {
    ClassBuilder(this).block()
    return this
}

@NodeDSL
fun clazz(
    access: Modifiers,
    name: String,
    superName: String = "java/lang/Object",
    interfaces: List<String>? = null,
    signature: String? = null,
    version: Int = Java7,
    block: (ClassBuilder.() -> Unit)? = null
): ClassNode = ClassNode().apply {
    this.access = access.modifier
    this.name = name
    this.version = version
    this.signature = signature
    this.superName = superName
    this.interfaces = interfaces ?: mutableListOf()
    if (block != null) modify(block)
}

@NodeDSL
fun clazz(
    access: Int,
    name: String,
    superName: String = "java/lang/Object",
    interfaces: List<String>? = null,
    signature: String? = null,
    version: Int = Java7,
    block: (ClassBuilder.() -> Unit)? = null
): ClassNode = ClassNode().apply {
    this.access = access
    this.name = name
    this.version = version
    this.signature = signature
    this.superName = superName
    this.interfaces = interfaces ?: mutableListOf()
    if (block != null) modify(block)
}