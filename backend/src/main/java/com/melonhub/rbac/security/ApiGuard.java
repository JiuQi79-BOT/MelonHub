package com.melonhub.rbac.security;

import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.service.AuthService;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiGuard extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String,Attempt> attempts=new ConcurrentHashMap<>();
    private record Attempt(long minute,int count) {}
    public ApiGuard(AuthService auth,ObjectMapper json) { this.auth=auth;this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Cache-Control","no-store");
        String path=request.getRequestURI();
        try {
            if(path.equals("/api/auth/login")) {
                long minute=System.currentTimeMillis()/60000;
                if(attempts.size()>10000) attempts.entrySet().removeIf(e->e.getValue().minute()<minute);
                Attempt a=attempts.compute(request.getRemoteAddr(),(k,v)->new Attempt(minute,v==null||v.minute()!=minute?1:v.count()+1));
                if(a.count()>10) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","登录尝试过多，请稍后重试");
            } else if(path.startsWith("/api/admin/") || path.equals("/api/auth/logout") || path.equals("/api/auth/me")) {
                request.setAttribute("actor",auth.admin(request.getHeader("Authorization")));
            } else if(path.startsWith("/api/v1/")) {
                request.setAttribute("applicationId",auth.application(request.getHeader("X-API-Key")));
            }
            chain.doFilter(request,response);
        } catch(ApiException e) {
            response.setStatus(e.status().value());response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(),Map.of("code",e.code(),"message",e.getMessage()));
        }
    }
}
