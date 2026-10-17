package com.melonhub.rbac.service;

import com.melonhub.rbac.dao.AuthDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.Credentials;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements ApplicationRunner {
    private final AuthDao dao;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String username;
    private final String password;
    public AuthService(AuthDao dao, @Value("${melonhub.admin-username}") String username, @Value("${melonhub.admin-password}") String password) {
        this.dao=dao; this.username=username; this.password=password;
    }
    @Override public void run(ApplicationArguments args) {
        if (password.length()<12) throw new IllegalStateException("ADMIN_PASSWORD must contain at least 12 characters");
        dao.saveAdmin(username,encoder.encode(password));
        dao.clearExpiredSessions();
    }
    @Transactional public Map<String,Object> login(Requests.Login input) {
        var hashes=dao.passwordHashes(input.username());
        if (hashes.isEmpty() || !encoder.matches(input.password(), hashes.get(0))) throw ApiException.unauthorized();
        String token=Credentials.token();
        dao.createSession(Credentials.hash(token),input.username(),Instant.now().plusSeconds(28800));
        return Map.of("token",token,"username",input.username(),"expiresIn",28800);
    }
    public String admin(String authorization) {
        String token=bearer(authorization);
        var names=dao.sessionUsers(Credentials.hash(token));
        if(names.isEmpty()) throw ApiException.unauthorized();
        return names.get(0);
    }
    public long application(String key) {
        var ids=dao.applications(Credentials.hash(key));
        if(ids.isEmpty()) throw ApiException.unauthorized();
        return ids.get(0);
    }
    static String bearer(String value) {
        if(value==null || !value.startsWith("Bearer ")) throw ApiException.unauthorized();
        return value.substring(7);
    }
    public void logout(String authorization) { dao.logout(Credentials.hash(bearer(authorization))); }
}
