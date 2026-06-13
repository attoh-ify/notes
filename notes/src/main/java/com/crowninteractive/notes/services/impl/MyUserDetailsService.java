package com.crowninteractive.notes.services.impl;

import com.crowninteractive.notes.entities.user.User;
import com.crowninteractive.notes.entities.user.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class MyUserDetailsService implements UserDetailsService {
    private final UserPolicyService userPolicyService;

    private static final Logger log =
            LoggerFactory.getLogger(MyUserDetailsService.class);

    public MyUserDetailsService(UserPolicyService userPolicyService) {
        this.userPolicyService = userPolicyService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.debug("Attempting to load user by email={}", email);

        User user = userPolicyService.userExists(email);

        log.debug("User loaded successfully email={} userId={}",
                user.getEmail(), user.getId());

        return new UserPrincipal(user);
    }
}
