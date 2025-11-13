package id.go.kemenkeu.djpbn.sakti.example;

import id.go.kemenkeu.djpbn.sakti.starter.SaktiTransactional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class SampleService {
    @Autowired
    private JdbcTemplate jdbcTemplateMain;

    @SaktiTransactional(lockKey = "'sample:' + #a0", idempotencyKey = "#a1")
    public void doWork(String orderNumber, String idempotencyKey) {
        // simple demo: write to main DB; other datasources could be used via repositories
        jdbcTemplateMain.update("CREATE TABLE IF NOT EXISTS demo (id VARCHAR(64), val VARCHAR(128))");
        jdbcTemplateMain.update("INSERT INTO demo(id, val) VALUES(?,?)", orderNumber, "processed");
    }
}
