package id.go.kemenkeu.djpbn.sakti.example;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.jdbc.DataSourceBuilder;
import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

@Configuration
public class ExampleConfig {
    // Three example H2 DataSources to simulate multi-DB environment (in-memory)
    @Bean(name = "dsMain")
    public DataSource dsMain() {
        return DataSourceBuilder.create().driverClassName("org.h2.Driver").url("jdbc:h2:mem:maindb;DB_CLOSE_DELAY=-1").username("sa").build();
    }
    @Bean(name = "ds1")
    public DataSource ds1() {
        return DataSourceBuilder.create().driverClassName("org.h2.Driver").url("jdbc:h2:mem:db1;DB_CLOSE_DELAY=-1").username("sa").build();
    }
    @Bean(name = "ds2")
    public DataSource ds2() {
        return DataSourceBuilder.create().driverClassName("org.h2.Driver").url("jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1").username("sa").build();
    }

    // expose a primary DataSource for TxLogRepository autowiring
    @Bean
    public DataSource mainDataSource() { return dsMain(); }

    @Bean
    public List<DataSource> saktiDataSources() { return Arrays.asList(dsMain(), ds1(), ds2()); }

    @Bean
    public JdbcTemplate jdbcTemplateMain(DataSource dsMain) { return new JdbcTemplate(dsMain); }
}
