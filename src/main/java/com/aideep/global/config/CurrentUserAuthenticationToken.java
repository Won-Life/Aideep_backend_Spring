package com.aideep.global.config;

import com.aideep.domain.auth.security.CurrentUser;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * CurrentUser를 principal로 노출하면서 원본 JWT도 로그아웃 등에 사용할 수 있게 보관한다.
 */
public class CurrentUserAuthenticationToken extends AbstractAuthenticationToken {
    private final CurrentUser currentUser;
    private final Jwt jwt;

    public CurrentUserAuthenticationToken(CurrentUser currentUser, Jwt jwt,
                                          Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.currentUser = currentUser;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public Object getPrincipal() {
        return currentUser;
    }

    @Override
    public String getName() {
        return currentUser.userId().toString();
    }
}
