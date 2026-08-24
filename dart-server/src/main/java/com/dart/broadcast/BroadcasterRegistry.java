package com.dart.broadcast;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成中会话的广播器注册表：send 创建会话广播器，resume 按会话获取订阅。
 * 移除放 finally（正常 done 与异常路径都清理，防泄漏）；服务重启后注册表丢失，
 * resume 走 completed 兜底（前端重载消息，不报错）。
 */
@Component
public class BroadcasterRegistry {

    private final ConcurrentHashMap<Long, SessionBroadcaster> broadcasters = new ConcurrentHashMap<>();

    public SessionBroadcaster register(Long conversationId) {
        return broadcasters.computeIfAbsent(conversationId, SessionBroadcaster::new);
    }

    public SessionBroadcaster get(Long conversationId) {
        return broadcasters.get(conversationId);
    }

    public void remove(Long conversationId) {
        broadcasters.remove(conversationId);
    }
}
