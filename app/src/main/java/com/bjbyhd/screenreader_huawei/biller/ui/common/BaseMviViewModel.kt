package com.bjbyhd.screenreader_huawei.biller.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

/**
 * MVI ViewModel 泛型基类 — 统一状态管理、事件分发、副作用发射
 *
 * 职责:
 *   - 持有唯一的 [MutableStateFlow<UiState>]，对外暴露只读 [StateFlow]
 *   - 提供 [updateState] 原子性状态更新
 *   - 提供 [launchSafe] 带统一异常处理的协程启动
 *   - 提供 [sendEffect] 一次性副作用发射（Toast、导航跳转、剪贴板写入等）
 *   - 子类实现 [onEvent] 处理用户交互事件
 *
 * @param initialUiState 初始 UI 状态快照
 */
abstract class BaseMviViewModel<UiState, Event>(
    initialUiState: UiState
) : ViewModel() {

    /**
     * 内部可变状态 — 仅 ViewModel 内部通过 [updateState] 修改
     *
     * 使用 @PublishedApi internal 避免子类意外直接修改，
     * 同时也允许 inline 函数 [updateState] 访问此属性。
     */
    @PublishedApi
    internal val _uiState: MutableStateFlow<UiState> = MutableStateFlow(initialUiState)

    /** 对外暴露的只读 StateFlow — View 层唯一数据来源 */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 协程异常处理器 — 捕获所有子协程未处理异常
     *
     * 策略: 记录日志但不崩溃。单个业务操作的异常不应影响 ViewModel 整体生命周期。
     * [viewModelScope] 使用 [SupervisorJob]（默认行为），确保子协程异常不会取消兄弟协程。
     */
    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        CLog.e(javaClass.simpleName, throwable) {
            "ViewModel 协程未捕获异常: ${throwable.message}"
        }
    }

    // ═══════════ Effect Channel — 一次性副作用 ═══════════

    /**
     * 内部 Effect Channel — 用于发射一次性副作用（Toast、导航跳转、剪贴板写入）
     *
     * 使用 [Channel.BUFFERED] 允许在无订阅者时缓存一条 Effect，
     * 避免 Fragment 在 STOPPED 状态时丢失 Effect。
     * 使用 `Any?` 类型而非第三个泛型参数，避免破坏现有子类。
     */
    private val _effect = Channel<Any?>(Channel.BUFFERED)

    /**
     * 对外暴露的 Effect Flow — View 层收集并消费一次性副作用
     *
     * 与 [uiState] 的 StateFlow 不同，Effect 通过 [Channel] 发射，
     * 每个 Effect 仅被消费一次，不会在 Fragment 恢复时重播。
     */
    val effect: Flow<Any?> = _effect.receiveAsFlow()

    /**
     * 发射一次性副作用
     *
     * 用于 Toast、导航跳转、剪贴板写入等"发射一次就遗忘"的事件。
     * 各 ViewModel 应定义自己的 sealed Effect 类型。
     *
     * @param effect 副作用对象（推荐使用 ViewModel 自己的 sealed Effect 子类型）
     */
    protected fun sendEffect(effect: Any?) {
        viewModelScope.launch { _effect.send(effect) }
    }

    // ═══════════ 抽象方法 — 子类必须实现 ═══════════

    /**
     * 事件分发入口 — View 层所有用户交互事件通过此方法传入
     *
     * 子类实现中通过 when 分支处理各类事件，调用 [launchSafe] 执行异步操作，
     * 通过 [updateState] 更新 UI 状态。
     *
     * @param event 用户交互事件（sealed interface 子类型）
     */
    abstract fun onEvent(event: Event)

    // ═══════════ 工具方法 — 子类直接使用 ═══════════

    /**
     * 原子性更新 UI 状态
     *
     * 使用 [MutableStateFlow.update] 保证在并发场景下的原子性。
     *
     * @param block 状态变更 lambda，接收当前状态，返回新状态（通过 copy() 生成）
     */
    protected inline fun updateState(block: UiState.() -> UiState) {
        _uiState.update(block)
    }

    /**
     * 在 ViewModel 作用域内启动带异常处理的协程
     *
     * 与直接调用 [viewModelScope.launch] 的区别:
     *   - 自动注入 [errorHandler]，异常不会导致 ViewModel 作用域崩溃
     *   - 默认运行在 [Dispatchers.Main.immediate]（[viewModelScope] 的默认调度器）
     *   - Room suspend 函数内部已自动切换到后台线程，调用方无需关心
     *   - 调用方不需要 try-catch 包裹
     *
     * **注意**：非 Room 的耗时操作（如大文件 I/O、JSON 解析）需调用方
     * 自行通过 [kotlinx.coroutines.withContext] 切换线程。
     *
     * @param block 挂起函数，在 viewModelScope 默认调度器执行
     */
    protected fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch(errorHandler) {
            block()
        }
    }
}
