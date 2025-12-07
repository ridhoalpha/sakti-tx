package id.go.kemenkeu.djpbn.sakti.tx.starter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration untuk SAKTI Transaction Coordinator
 * 
 * FEATURES:
 * - Admin API protection dengan RBAC
 * - Method-level security (@PreAuthorize)
 * - Audit logging untuk admin actions
 * - Configurable via properties
 * 
 * ROLES:
 * - SAKTI_ADMIN: Full access to admin API (retry, delete, force-scan)
 * - SAKTI_OPERATOR: Read-only access (view failed transactions, metrics)
 * - ACTUATOR_ADMIN: Access to /actuator endpoints
 * 
 * PRODUCTION NOTES:
 * - Replace InMemoryUserDetailsManager dengan LDAP/Database
 * - Enable HTTPS/TLS
 * - Configure session management
 * - Add IP whitelist for admin API
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@ConditionalOnProperty(prefix = "sakti.tx.security", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SaktiTxSecurityConfig {
    
    private static final Logger log = LoggerFactory.getLogger(SaktiTxSecurityConfig.class);
    
    private final SaktiTxProperties properties;
    
    public SaktiTxSecurityConfig(SaktiTxProperties properties) {
        this.properties = properties;
        log.info("═══════════════════════════════════════════════════════════");
        log.info("SAKTI TX Security Configuration - ENABLED");
        log.info("═══════════════════════════════════════════════════════════");
    }
    
    /**
     * Main Security Filter Chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        
        log.info("Configuring Security Filter Chain...");
        
        http
            // ═══════════════════════════════════════════════════════════════
            // Authorization Rules
            // ═══════════════════════════════════════════════════════════════
            .authorizeHttpRequests(auth -> auth
                // Admin API - CRITICAL endpoints
                .requestMatchers("/admin/transactions/retry/**").hasRole("SAKTI_ADMIN")
                .requestMatchers("/admin/transactions/force-scan").hasRole("SAKTI_ADMIN")
                .requestMatchers("/admin/transactions/{txId}").hasRole("SAKTI_ADMIN")
                
                // Admin API - Read-only endpoints
                .requestMatchers("/admin/transactions/failed").hasAnyRole("SAKTI_ADMIN", "SAKTI_OPERATOR")
                .requestMatchers("/admin/transactions/metrics").hasAnyRole("SAKTI_ADMIN", "SAKTI_OPERATOR")
                .requestMatchers("/admin/transactions/health").hasAnyRole("SAKTI_ADMIN", "SAKTI_OPERATOR")
                
                // Actuator endpoints
                .requestMatchers("/actuator/health").permitAll() // Public health check
                .requestMatchers("/actuator/**").hasRole("ACTUATOR_ADMIN")
                
                // All other requests require authentication
                .anyRequest().authenticated()
            )
            
            // ═══════════════════════════════════════════════════════════════
            // Authentication Method
            // ═══════════════════════════════════════════════════════════════
            .httpBasic(Customizer.withDefaults()) // Basic Auth for now
            
            // ═══════════════════════════════════════════════════════════════
            // CSRF Protection
            // ═══════════════════════════════════════════════════════════════
            // IMPORTANT: For REST API, CSRF can be disabled
            // But if you have web forms, keep it enabled
            .csrf(csrf -> {
                if (properties.getSecurity().isCsrfEnabled()) {
                    log.info("CSRF Protection: ENABLED");
                    csrf.ignoringRequestMatchers("/admin/transactions/**"); // API endpoints
                } else {
                    log.warn("CSRF Protection: DISABLED (not recommended for production)");
                    csrf.disable();
                }
            })
            
            // ═══════════════════════════════════════════════════════════════
            // Session Management
            // ═══════════════════════════════════════════════════════════════
            .sessionManagement(session -> {
                session.maximumSessions(1); // One session per user
                session.sessionFixation().migrateSession(); // Prevent session fixation
            })
            
            // ═══════════════════════════════════════════════════════════════
            // Logout Configuration
            // ═══════════════════════════════════════════════════════════════
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );
        
        log.info("✓ Security Filter Chain configured successfully");
        log.info("  - Admin API: Protected with RBAC");
        log.info("  - Authentication: HTTP Basic (configure OAuth2 for production)");
        log.info("  - CSRF: {}", properties.getSecurity().isCsrfEnabled() ? "ENABLED" : "DISABLED");
        
        return http.build();
    }
    
    /**
     * ═══════════════════════════════════════════════════════════════════════
     * USER DETAILS SERVICE - IN-MEMORY (DEVELOPMENT ONLY)
     * ═══════════════════════════════════════════════════════════════════════
     * 
     * PRODUCTION: Replace dengan:
     * - LDAP (Active Directory)
     * - Database (JPA UserDetailsService)
     * - OAuth2 (Keycloak, Okta)
     * - SAML SSO
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        
        log.warn("═══════════════════════════════════════════════════════════");
        log.warn("Using IN-MEMORY User Details Service");
        log.warn("THIS IS FOR DEVELOPMENT/TESTING ONLY!");
        log.warn("PRODUCTION: Replace with LDAP/Database/OAuth2");
        log.warn("═══════════════════════════════════════════════════════════");
        
        // ═══════════════════════════════════════════════════════════════
        // SAKTI_ADMIN - Full access
        // ═══════════════════════════════════════════════════════════════
        UserDetails admin = User.builder()
            .username(properties.getSecurity().getDefaultAdminUsername())
            .password(passwordEncoder.encode(properties.getSecurity().getDefaultAdminPassword()))
            .roles("SAKTI_ADMIN", "ACTUATOR_ADMIN")
            .build();
        
        // ═══════════════════════════════════════════════════════════════
        // SAKTI_OPERATOR - Read-only access
        // ═══════════════════════════════════════════════════════════════
        UserDetails operator = User.builder()
            .username("operator")
            .password(passwordEncoder.encode("operator123")) // Change this!
            .roles("SAKTI_OPERATOR")
            .build();
        
        log.info("Created default users:");
        log.info("  - Admin: {} (roles: SAKTI_ADMIN, ACTUATOR_ADMIN)", 
            properties.getSecurity().getDefaultAdminUsername());
        log.info("  - Operator: operator (roles: SAKTI_OPERATOR)");
        log.warn("⚠ CHANGE DEFAULT PASSWORDS IMMEDIATELY!");
        
        return new InMemoryUserDetailsManager(admin, operator);
    }
    
    /**
     * Password Encoder - BCrypt (industry standard)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        log.info("Password Encoder: BCrypt (strength: 12)");
        return new BCryptPasswordEncoder(12);
    }
}