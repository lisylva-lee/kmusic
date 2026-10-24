package com.kmusic.apkhd.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.kmusic.apkhd.MoyinApp

/**
 * ViewModel 构造期不能崩。
 *
 * 这条测试是为了钉死一个真实踩过的坑：`init` 里的
 * `viewModelScope.launch { flow.collect { state = it } }` **会同步执行** ——
 * viewModelScope 用的是 Dispatchers.Main.immediate，且 StateFlow 首次 emit 不挂起，
 * 所以 lambda 体会在构造过程中就直接跑。
 *
 * 后果：只要被赋值的属性**声明在 init 之后**，那一刻委托字段还是 null，
 * 直接 NPE，App 启动闪退。
 *
 * 所以规则是：**init 里碰到的属性必须声明在 init 之前**（或者把 init 挪到类末尾）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MoyinApp::class, sdk = [34])
class ViewModelInitTest {

    @Test
    fun constructsWithoutCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MoyinViewModel(app)
        assertNotNull("ViewModel 必须能构造出来", vm)
    }
}
