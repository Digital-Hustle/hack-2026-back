package ru.cashflow.authprofile.api.controller;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import ru.cashflow.authprofile.api.ApiPaths;
import ru.cashflow.authprofile.api.dto.request.UpdateProfileRq;
import ru.cashflow.authprofile.api.dto.response.ProfileRs;
import ru.cashflow.authprofile.api.security.CurrentUser;

@RequestMapping(path = ApiPaths.PROFILE, produces = MediaType.APPLICATION_JSON_VALUE)
public interface ProfileController {

    @GetMapping("/me")
    ProfileRs getMe(@AuthenticationPrincipal CurrentUser user);

    @PutMapping(path = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    ProfileRs updateMe(
            @Valid @RequestBody UpdateProfileRq request, @AuthenticationPrincipal CurrentUser user);
}
