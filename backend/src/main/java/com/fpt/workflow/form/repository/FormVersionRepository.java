package com.fpt.workflow.form.repository;
import com.fpt.workflow.form.domain.FormVersion;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormVersionRepository extends JpaRepository<FormVersion,UUID>{ List<FormVersion> findAllByFormIdOrderByVersionNoDesc(UUID formId); long countByFormId(UUID formId); }
