package com.fpt.workflow.ticketcategory;
import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
@Testcontainers
class BusinessIntentMigrationIT {
  @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("workflow_v42_upgrade_test").withUsername("workflow_test").withPassword("workflow_test");
  @Test void upgradesV41ToV42AndCreatesCategoryKeyBindingModel()throws Exception{
    Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").target(MigrationVersion.fromVersion("41")).load().migrate();
    Flyway current=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load();
    assertThat(current.migrate().migrationsExecuted).isEqualTo(4);
    assertThat(currentVersion()).isEqualTo("45");
    assertThat(tableExists("forms")).isTrue();assertThat(tableExists("ticket_categories")).isTrue();assertThat(tableExists("ticket_category_mappings")).isTrue();assertThat(tableExists("event_workflow_input_snapshots")).isTrue();assertThat(tableExists("ticket_state_history")).isTrue();assertThat(tableExists("tenants")).isTrue();assertThat(tableExists("tenant_memberships")).isTrue();assertThat(tableExists("ticket_category_workflow_bindings")).isTrue();
    assertThat(tableExists("event_workflow_input_revisions")).isTrue();
    assertThat(columnExists("revision_requests","input_revision")).isTrue();
    assertThat(columnExists("tickets","ticket_category_version_id")).isTrue();assertThat(uniqueKeyExists("ticket_categories","ticket_categories_key_key")).isTrue();
  }
  private String currentVersion()throws Exception{try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.createStatement();var r=s.executeQuery("select version from flyway_schema_history where success order by installed_rank desc limit 1")){r.next();return r.getString(1);}}
  private boolean tableExists(String name)throws Exception{return scalar("select to_regclass('public.' || ?) is not null",name,null);}
  private boolean columnExists(String table,String column)throws Exception{return scalar("select exists(select 1 from information_schema.columns where table_schema='public' and table_name=? and column_name=?)",table,column);}
  private boolean uniqueKeyExists(String table,String constraint)throws Exception{return scalar("select exists(select 1 from information_schema.table_constraints where table_schema='public' and table_name=? and constraint_name=? and constraint_type='UNIQUE')",table,constraint);}
  private boolean scalar(String sql,String first,String second)throws Exception{try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.prepareStatement(sql)){s.setString(1,first);if(second!=null)s.setString(2,second);try(var r=s.executeQuery()){r.next();return r.getBoolean(1);}}}
}
