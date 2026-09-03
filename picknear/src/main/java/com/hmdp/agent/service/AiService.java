package com.hmdp.agent.service;

import com.hmdp.agent.observability.api.AgentSpan;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
* AI服务接口 — 对接 AI 对话能力
*/
public interface AiService {

   /**
    * 流式模式：通过 SSE (Server-Sent Events) 逐段推送 AI 回复，包含工具调用,这里做的是伪流式，提前调用工具，再推送结果
    *
    * @param content 用户输入
    * @param conversationId 会话 ID（多轮对话标识，预留，暂传 "default"）
    * @param emitter SSE 发射器，用于推送逐段结果
    * @param rootSpan 观测根 span（由 Controller 创建，随调用显式传递，异步线程 resume 挂载）
    */
   void chatWithToolcall(String content, String conversationId, SseEmitter emitter, AgentSpan rootSpan);
}
