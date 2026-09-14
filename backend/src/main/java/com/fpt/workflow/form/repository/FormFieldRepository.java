package com.fpt.workflow.form.repository;
import com.fpt.workflow.form.domain.FormField;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormFieldRepository extends JpaRepository<FormField,UUID>{List<FormField> findAllByFormVersionIdOrderByOrdinalAsc(UUID versionId); void deleteAllByFormVersionId(UUID versionId);}
