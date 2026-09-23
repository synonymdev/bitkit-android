package to.bitkit.services

import org.lightningdevkit.ldknode.Builder

/** Applied to the ldk-node [Builder] before the node is built. Contributed through a Hilt set multibinding. */
fun interface NodeBuilderCustomizer {
    suspend fun customize(builder: Builder)
}
