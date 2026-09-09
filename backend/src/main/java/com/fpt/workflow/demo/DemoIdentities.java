package com.fpt.workflow.demo;

import java.util.List;
import java.util.UUID;

public final class DemoIdentities {
  private DemoIdentities() {}

  // System & Admin
  public static final UUID ADMIN_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  public static final UUID ADMIN_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000101");

  // Personas
  public static final UUID HR_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
  public static final UUID HR_EMPLOYEE_ID = UUID.fromString("10000000-0000-4000-8000-000000000102");

  public static final UUID SECURITY_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000003");
  public static final UUID SECURITY_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000103");

  public static final UUID FINANCE_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000004");
  public static final UUID FINANCE_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000104");

  public static final UUID LEGAL_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000005");
  public static final UUID LEGAL_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000105");

  // Management Hierarchy
  public static final UUID DIRECTOR_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000010");
  public static final UUID DIRECTOR_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000110");

  public static final UUID MANAGER_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000011");
  public static final UUID MANAGER_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000111");

  // Demo Employees
  public static final UUID EMPLOYEE_A_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000021");
  public static final UUID EMPLOYEE_A_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000121");

  public static final UUID EMPLOYEE_B_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000022");
  public static final UUID EMPLOYEE_B_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000122");

  public static final UUID EMPLOYEE_C_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000023");
  public static final UUID EMPLOYEE_C_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000123");

  public static final UUID EMPLOYEE_D_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000024");
  public static final UUID EMPLOYEE_D_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000124");

  public static final UUID EMPLOYEE_E_USER_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000025");
  public static final UUID EMPLOYEE_E_EMPLOYEE_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000125");

  public static final List<UUID> DEMO_SUBJECT_USER_IDS =
      List.of(
          EMPLOYEE_A_USER_ID,
          EMPLOYEE_B_USER_ID,
          EMPLOYEE_C_USER_ID,
          EMPLOYEE_D_USER_ID,
          EMPLOYEE_E_USER_ID);

  // Org Units
  public static final UUID UNIT_ENG_ID = UUID.fromString("10000000-0000-4000-8000-000000000201");
  public static final UUID UNIT_HR_ID = UUID.fromString("10000000-0000-4000-8000-000000000202");
  public static final UUID UNIT_SEC_ID = UUID.fromString("10000000-0000-4000-8000-000000000203");
  public static final UUID UNIT_FIN_ID = UUID.fromString("10000000-0000-4000-8000-000000000204");
  public static final UUID UNIT_LEG_ID = UUID.fromString("10000000-0000-4000-8000-000000000205");

  // Positions
  public static final UUID POS_DIR_ENG_ID = UUID.fromString("10000000-0000-4000-8000-000000000310");
  public static final UUID POS_MGR_ENG_ID = UUID.fromString("10000000-0000-4000-8000-000000000311");
  public static final UUID POS_STAFF_ENG_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000320");
  public static final UUID POS_HR_LEAD_ID = UUID.fromString("10000000-0000-4000-8000-000000000302");
  public static final UUID POS_SEC_LEAD_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000303");
  public static final UUID POS_FIN_LEAD_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000304");
  public static final UUID POS_LEG_LEAD_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000305");

  // Workflow Keys
  public static final String KEY_LEAVE_REQUEST = "leave_request";
  public static final String KEY_ACCESS_REQUEST = "access_request";
  public static final String KEY_VENDOR_VERIFICATION = "vendor_verification";
  public static final String KEY_PURCHASE_REQUEST = "purchase_request";
  public static final String KEY_EMPLOYEE_EVALUATION = "employee_evaluation";

  // Request Type Keys
  public static final String REQ_LEAVE_REQUEST = "LEAVE_REQUEST";
  public static final String REQ_ACCESS_REQUEST = "ACCESS_REQUEST";
  public static final String REQ_PURCHASE_REQUEST = "PURCHASE_REQUEST";
  public static final String REQ_EMPLOYEE_EVALUATION = "EMPLOYEE_EVALUATION";

  // Connector Keys
  public static final String CONNECTOR_IAM = "DEMO_IAM";
  public static final String ACTION_PROVISION = "PROVISION_ACCESS";
  public static final String CONNECTOR_ERP = "DEMO_ERP";
  public static final String ACTION_CREATE_PO = "CREATE_PURCHASE_ORDER";
}
