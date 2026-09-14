package com.fpt.workflow.form.repository;
import com.fpt.workflow.form.domain.FormDefinition;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormDefinitionRepository extends JpaRepository<FormDefinition,UUID>{ Optional<FormDefinition> findByKey(String key); }
