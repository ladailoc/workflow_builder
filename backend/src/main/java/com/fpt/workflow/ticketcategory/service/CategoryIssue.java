package com.fpt.workflow.ticketcategory.service;
public record CategoryIssue(String code,String severity,String resourceType,String resourceId,String fieldPath,String message){}
