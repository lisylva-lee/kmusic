package com.kmusic.apkhd.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 探针：`init` 里 collect 一个**裸 StateFlow** 去赋值一个**声明在后面**的
 * `by mutableStateOf` 属性，到底会不会在构造期同步踩到未初始化的委托？
 *
 * viewModelScope 用的是 Dispatchers.Main.immediate；StateFlow 首次 emit 不挂起。
 * 两者叠加就可能让 lambda 体在 <init> 期间就执行，而那时委托字段还是 null。
 *
 * 这个探针只打印事实，不断言 —— 因为结论要用来决定怎么改，不能反过来被断言带偏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InitOrderingProbeTest {

    private class LateAssignment(scope: CoroutineScope) {
        init {
            scope.launch {
                MutableStateFlow("from-flow").collect { late = it }
            }
        }

        // ⚠️ 故意声明在 init **之后**，复刻被怀疑的写法
        var late by mutableStateOf("initial")
    }

    private class EarlyAssignment(scope: CoroutineScope) {
        var early by mutableStateOf("initial")

        init {
            scope.launch {
                MutableStateFlow("from-flow").collect { early = it }
            }
        }
    }

    private fun mainImmediateScope(): Pair<CoroutineScope, MutableList<Throwable>> {
        val caught = mutableListOf<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> caught += e }
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler) to caught
    }

    @Test
    fun probeLateDeclaration() {
        val (scope, caught) = mainImmediateScope()
        val p = runCatching { LateAssignment(scope) }.getOrElse { null }
        println("【探针】声明在 init 之后 → 结果=${p?.late}, 协程内异常=${caught.map { it::class.simpleName + ": " + it.message }}")
    }

    @Test
    fun probeEarlyDeclaration() {
        val (scope, caught) = mainImmediateScope()
        val p = runCatching { EarlyAssignment(scope) }.getOrElse { null }
        println("【探针】声明在 init 之前 → 结果=${p?.early}, 协程内异常=${caught.size}")
    }
}
