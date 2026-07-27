package com.chatai.newbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 回退控制器：将不带文件扩展名的路径请求转发到 /index.html，
 * 解决 Vue Router history 模式下刷新 404 的问题。
 * 带 . 的静态资源（js/css/图片等）由 Spring 资源处理器正常返回。
 * /api/** 由 RestController 处理，优先级高于此处。
 */
@Controller
public class SpaForwardController {

    // /admin 多级路径回退
    @GetMapping({"/admin", "/admin/{path:[^\\.]*}", "/admin/{path1:[^\\.]*}/{path2:[^\\.]*}"})
    public String forwardAdmin() {
        return "forward:/index.html";
    }

    // 全站单级路径回退（如 /login）—— 不含点号的路径均转发到 Vue 入口
    @GetMapping("/{path:[^\\.]*}")
    public String forwardApp() {
        return "forward:/index.html";
    }
}
