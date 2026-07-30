package com.chatai.newbot.exception;

/**
 * 无权限异常（HTTP 403）：控制器内权限校验不通过时抛出，
 * 作为拦截器统一拦截之外的纵深防御。
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException() {
        super(403, "无权限");
    }
}
