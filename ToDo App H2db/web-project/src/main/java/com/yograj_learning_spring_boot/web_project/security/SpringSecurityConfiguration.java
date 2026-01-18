package com.yograj_learning_spring_boot.web_project.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.function.Function;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SpringSecurityConfiguration {

    @Bean
   public InMemoryUserDetailsManager createUserDetailsManager(){

        UserDetails userDetails = createNewUser("yograj", "1234");
        UserDetails userDetails1=createNewUser("rama","9999");
        UserDetails userDetails2=createNewUser("Yograj Tripathi","1008");
        return new InMemoryUserDetailsManager(userDetails,userDetails1,userDetails2);
    }


    private UserDetails createNewUser(String username, String password) {
        Function<String, String> passwordEncoder
                =input->passwordEncoder().encode(input);


        UserDetails userDetails=   User.builder()
                   .passwordEncoder(passwordEncoder)
                   .username(username)
                   .password(password)
                   .roles("USER","ADMIN")
                   .build();
        return userDetails;
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(withDefaults())
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/h2-console/**")
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        return http.build();
    }

}
