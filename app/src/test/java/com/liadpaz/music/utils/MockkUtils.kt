package com.liadpaz.music.utils

import io.mockk.mockk
import kotlin.reflect.KClass

inline fun <reified T> relaxedMockk(
    name: String? = null,
    vararg moreInterfaces: KClass<*>,
    relaxUnitFun: Boolean = false,
    block: T.() -> Unit = {}
): T = mockk(
    name = name,
    relaxed = true,
    moreInterfaces = moreInterfaces,
    relaxUnitFun = relaxUnitFun,
    block = block
)