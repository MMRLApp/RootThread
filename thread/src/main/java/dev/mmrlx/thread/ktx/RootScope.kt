package dev.mmrlx.thread.ktx

interface RootScope
internal class RootScopeImpl : RootScope

/**
 * The default instance of [RootScope].
 */
val rootScope: RootScope = RootScopeImpl()