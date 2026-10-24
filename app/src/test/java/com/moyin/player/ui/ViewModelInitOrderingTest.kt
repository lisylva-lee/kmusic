package com.kmusic.apkhd.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 结构性回归：`MoyinViewModel` 的 `init` 块必须留在**所有** `by mutableStateOf` 声明之后。
 *
 * 为什么这条值得单独测：
 *
 * `init { viewModelScope.launch { flow.collect { state = it } } }` 里的 lambda 体
 * 会**同步执行** —— viewModelScope 用的是 `Dispatchers.Main.immediate`，
 * 且裸 StateFlow 的首次 emit 不挂起。
 *
 * 于是只要被赋值的 `by mutableStateOf` 属性声明在 init 之后，
 * 执行到那一句时**委托字段还是 null**，直接 NPE。
 * 在协程里这个异常会被吞掉（编译期无警告、单测也未必失败），
 * 但真机上没人接住 → 抛到主线程 → **启动闪退**。
 *
 * 这个坑已经在 0.9.0 真实发生过一次，所以用一条机械检查钉死：
 * init 挪到属性之前 = 测试直接红。
 */
class ViewModelInitOrderingTest {

    private fun sourceFile(): File {
        // 项目早就从 com.moyin.player 改名为 com.kmusic.apkhd，这里要跟着换。
        // 用多候选是因为 Gradle 单测的工作目录是 app/，而 IDE 里跑是项目根 —— 两边都要能过。
        val candidates = listOf(
            "src/main/java/com/kmusic/apkhd/ui/MoyinViewModel.kt",
            "app/src/main/java/com/kmusic/apkhd/ui/MoyinViewModel.kt",
            "src/main/java/com/moyin/player/ui/MoyinViewModel.kt",
            "app/src/main/java/com/moyin/player/ui/MoyinViewModel.kt"
        )
        return candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 MoyinViewModel.kt，检查测试的工作目录：${File(".").absolutePath}")
    }

    @Test
    fun initBlocksMustComeAfterEveryStateDeclaration() {
        val src = sourceFile().readText()

        val firstInit = src.indexOf("    init {")
        assertTrue("源码里应该有 init 块", firstInit >= 0)

        val lastState = src.lastIndexOf("by mutableStateOf")
        assertTrue("源码里应该有 by mutableStateOf 声明", lastState >= 0)

        assertTrue(
            "MoyinViewModel 的 init 块出现在 by mutableStateOf 声明之前 —— " +
                "collect 会同步执行，属性委托还没初始化，构造期 NPE（真机闪退）。" +
                "请把 init 块挪到类末尾。",
            firstInit > lastState
        )
    }

    @Test
    fun initBlocksMustNotDeclareComposeState() {
        val src = sourceFile().readText()
        val firstInit = src.indexOf("    init {")
        val body = src.substring(firstInit)
        // init 里声明 by mutableStateOf 同样会踩坑（先赋值、后被初始化覆盖）
        assertTrue(
            "init 块里不要声明 by mutableStateOf 属性",
            !body.contains("by mutableStateOf") ||
                body.indexOf("by mutableStateOf") > body.indexOf("override fun onCleared")
        )
    }
}
