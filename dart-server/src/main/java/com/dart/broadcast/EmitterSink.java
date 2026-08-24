package com.dart.broadcast;

import com.dart.util.SseUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单订阅者 SseSink 适配器：包装单个 SseEmitter，行为与现有 SseUtils 直推一致
 * （发送失败吞异常不中断主流程）。用于集成测试与无广播需求的调用场景。
 */
public class EmitterSink implements SseSink {

    private final SseEmitter emitter;

    public EmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void sendEvent(String eventName, Object data) {
        SseUtils.sendEvent(emitter, eventName, data);
    }

    @Override
    public void sendStatus(String phase, String message) {
        SseUtils.sendStatus(emitter, phase, message);
    }

    @Override
    public void sendError(int code, String message) {
        SseUtils.sendError(emitter, code, message);
    }

    @Override
    public void complete() {
        SseUtils.completeSafe(emitter);
    }

    @Override
    public void completeWithError(Throwable t) {
        SseUtils.errorSafe(emitter, t);
    }
}
