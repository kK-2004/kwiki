package com.kwiki.indexing.version;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/** 只为无扩展名的 /admin 客户端路由返回 SPA；永不覆盖 API/actuator。 */
@Controller
public class AdminSpaController {
    @GetMapping({"/admin", "/admin/", "/admin/{*path}"})
    public String admin(HttpServletRequest request) {
        String path=request.getRequestURI();
        if(!path.equals("/admin")&&!path.equals("/admin/")
                && path.substring(path.lastIndexOf('/')+1).contains(".")){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "forward:/admin/index.html";
    }
}
