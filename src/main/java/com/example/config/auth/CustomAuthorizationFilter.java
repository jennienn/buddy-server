package com.example.config.auth;

import com.example.config.jwt.TokenProvider;
import com.example.config.redis.RedisUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class CustomAuthorizationFilter extends OncePerRequestFilter {       //http 요청마다 처리하도록 하는 필터

    private final TokenProvider tokenProvider;
    private final Authenticator authenticator;
    private final RedisUtil redisUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String servletPath = request.getServletPath();

        if(servletPath.equals("/auth/signup") || servletPath.equals("auth/login")) {
            filterChain.doFilter(request, response);        //다음 필터 실행
            return;
        }

        log.info("CustomAuthorizationFilter.class / doFilterInternal :" + servletPath +  ": 엑세스 토큰을 검사");

        boolean nowCreated = false;         //엑세스 토큰이 이번에 만들어진 것인지 확인하는 변수
        String accessToken = tokenProvider.resolveToken(request);

        // 엑세스 토큰 없으면 -> 다시 로그인 해야함
        if(!StringUtils.hasText(accessToken)){
            log.info("CustomAuthorizationFilter.class / doFilterInternal : 엑세스 토큰 없음");

            String refreshToken = tokenProvider.getRefreshToken(request);

            if(refreshToken != null){
                boolean refreshTokenValid = tokenProvider.validateRefreshToken(refreshToken);
                if(refreshTokenValid){      //리프레시 토큰이 유효하면 엑세스 토큰 새로 발급

                    accessToken = tokenProvider.createNewAccessToken(refreshToken, response);
                    nowCreated = true;
                }
                else{
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    return;
                }
            }
            else{
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }
        }

        // 블랙리스트에 있으면 로그아웃되었거나, 토큰이 만료된 상태인 것임. 401에러 -> 다시 로그인 해야함.
        if(!nowCreated && redisUtil.hasKey(accessToken)){
            log.info("CustomAuthorizationFilter.class / doFilterInternal : 블랙리스트에 등록된 엑세스 토큰");

            if(servletPath.equals("/attendance/member-attended")) filterChain.doFilter(request, response);        //다음 필터 실행

            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        // access 토큰 유효성 검증
        String validatedAccessToken = tokenProvider.validateAccessToken(accessToken, request, response);

        if(validatedAccessToken != null){
            Authentication authentication = tokenProvider.getAuthentication(validatedAccessToken);

            //토큰을 통해 생성한 Authentication 객체 스프링 시큐리티 컨텍스트에 저장
            authenticator.setAuthenticationInSecurityContext(authentication);

        }
        else{
            log.info("CustomAuthorizationFilter.class / doFilterInternal : JWT access 토큰, refresh 토큰 모두 유효하지 않음");

            if(servletPath.equals("/attendance/member-attended")) filterChain.doFilter(request, response);        //다음 필터 실행

            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        filterChain.doFilter(request, response);        //다음 필터 실행
    }
}
