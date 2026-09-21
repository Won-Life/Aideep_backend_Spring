package com.aideep.domain.user.controller;

import com.aideep.domain.auth.security.UserDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/v1/api/aideep/user")
public class UserController {

    @GetMapping("/")
    public String hello() {
        return "hello";
    }

    @GetMapping("/user")
    public void helloUser(@AuthenticationPrincipal UserDetail userDetail) {
        log.debug(userDetail.userName());
    }
}
