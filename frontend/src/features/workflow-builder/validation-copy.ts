import type { ValidationIssue } from "./types";

export interface ValidationIssuePresentation {
  message: string;
  suggestion: string;
}

function quotedValues(message: string): string[] {
  return Array.from(message.matchAll(/["']([^"']+)["']/g)).map(
    (match) => match[1],
  );
}

function nodeLabel(issue: ValidationIssue): string {
  const [label] = quotedValues(issue.message);
  return issue.nodeLabel ?? label ?? "này";
}

function portDetails(issue: ValidationIssue): {
  port: string;
  node: string;
} {
  const [port, node] = quotedValues(issue.message);
  return { port: port ?? "này", node: node ?? nodeLabel(issue) };
}

/**
 * Converts validator output into short, actionable copy for end users.
 * The original code and identifiers remain available in the technical details
 * section so support teams can still trace an issue without cluttering the UI.
 */
export function getValidationIssuePresentation(
  issue: ValidationIssue,
): ValidationIssuePresentation {
  switch (issue.code) {
    case "ERR_NO_START":
    case "NO_START":
      return {
        message: "Quy trình chưa có bước Bắt đầu.",
        suggestion: "Thêm một bước Bắt đầu rồi nối tới bước đầu tiên.",
      };
    case "ERR_MULTI_START":
    case "MULTIPLE_START":
      return {
        message: "Quy trình đang có nhiều bước Bắt đầu.",
        suggestion: "Chỉ giữ lại một bước Bắt đầu trong sơ đồ.",
      };
    case "ERR_NO_END":
    case "NO_END":
      return {
        message: "Quy trình chưa có bước Kết thúc.",
        suggestion: "Thêm ít nhất một bước Kết thúc và nối nhánh cuối vào đó.",
      };
    case "ERR_START_INCOMING":
    case "START_HAS_INCOMING":
      return {
        message: `Bước Bắt đầu “${nodeLabel(issue)}” đang có đường nối đi vào.`,
        suggestion: "Xóa đường nối đi vào bước Bắt đầu.",
      };
    case "ERR_END_OUTGOING":
    case "END_HAS_OUTGOING":
      return {
        message: `Bước Kết thúc “${nodeLabel(issue)}” đang có đường nối đi ra.`,
        suggestion: "Xóa đường nối đi ra khỏi bước Kết thúc.",
      };
    case "WARN_UNREACHABLE_NODE":
    case "UNREACHABLE_NODE":
      return {
        message: `Bước “${nodeLabel(issue)}” chưa được nối từ bước trước.`,
        suggestion:
          "Nối bước này với một bước trước đó hoặc xóa nếu không dùng.",
      };
    case "WARN_DEAD_END":
    case "NONTERMINAL_DEAD_END":
      return {
        message: `Bước “${nodeLabel(issue)}” chưa có đường nối đi tiếp.`,
        suggestion: "Nối bước này tới bước tiếp theo hoặc đổi thành Kết thúc.",
      };
    case "ERR_PORT_UNCONNECTED": {
      const { port, node } = portDetails(issue);
      return {
        message: `Cổng “${port}” của bước “${node}” chưa được nối.`,
        suggestion: "Nối cổng này tới bước tiếp theo để xử lý nhánh tương ứng.",
      };
    }
    case "UNHANDLED_PORT":
      return {
        message: `Bước “${nodeLabel(issue)}” còn một nhánh đầu ra chưa được xử lý.`,
        suggestion:
          "Nối nhánh này tới bước tiếp theo hoặc cấu hình cách kết thúc nhánh.",
      };
    case "DANGLING_EDGE":
    case "MISSING_TARGET":
      return {
        message: "Có một đường nối đang trỏ tới bước không còn tồn tại.",
        suggestion: "Xóa đường nối lỗi rồi tạo lại đường nối tới bước hợp lệ.",
      };
    case "INVALID_OUTPUT_PORT":
      return {
        message: "Một đường nối đang dùng cổng đầu ra không hợp lệ.",
        suggestion:
          "Chọn lại cổng đầu ra có trên bước rồi nối tới bước tiếp theo.",
      };
    case "NODE_KEY_REQUIRED":
      return {
        message: `Bước “${nodeLabel(issue)}” chưa có mã bước.`,
        suggestion: "Mở cấu hình bước và nhập một mã duy nhất.",
      };
    case "DUPLICATE_NODE_KEY":
      return {
        message: `Mã của bước “${nodeLabel(issue)}” đang bị trùng.`,
        suggestion: "Đổi mã bước thành giá trị duy nhất trong quy trình.",
      };
    case "TASK_PARTICIPANT_REQUIRED":
      return {
        message: `Bước “${nodeLabel(issue)}” chưa có người xử lý.`,
        suggestion: "Mở cấu hình Người xử lý và chọn cách xác định người nhận.",
      };
    case "INVALID_PARTICIPANT_CONFIG":
    case "PARTICIPANT_TYPE_REQUIRED":
    case "UNKNOWN_PARTICIPANT_TYPE":
      return {
        message: `Cấu hình người xử lý của bước “${nodeLabel(issue)}” chưa hợp lệ.`,
        suggestion: "Mở phần Người xử lý và chọn một cách xác định hợp lệ.",
      };
    case "FIXED_USER_ID_REQUIRED":
    case "ROLE_MEMBERS_KEY_REQUIRED":
    case "GROUP_MEMBERS_KEY_REQUIRED":
    case "REQUEST_FIELD_KEY_REQUIRED":
      return {
        message: `Bước “${nodeLabel(issue)}” còn thiếu thông tin người xử lý.`,
        suggestion:
          "Chọn hoặc nhập đầy đủ thông tin cho cách xác định đã chọn.",
      };
    case "ALLOWED_ACTIONS_EMPTY":
      return {
        message: `Bước “${nodeLabel(issue)}” chưa có kết quả xử lý.`,
        suggestion: "Chọn ít nhất một kết quả đầu ra cho bước này.",
      };
    case "CONDITION_NOT_BOOLEAN":
      return {
        message: `Điều kiện của bước “${nodeLabel(issue)}” chưa trả về Đúng hoặc Sai.`,
        suggestion:
          "Tạo điều kiện bằng phép so sánh để kết quả là Đúng hoặc Sai.",
      };
    case "INVALID_EXPRESSION":
    case "INVALID_TYPED_REFERENCE":
      return {
        message: `Biểu thức tại bước “${nodeLabel(issue)}” chưa hợp lệ.`,
        suggestion:
          "Mở phần điều kiện và chọn lại trường cùng kiểu dữ liệu phù hợp.",
      };
    case "UNKNOWN_WORKFLOW_INPUT":
    case "INPUTS_BARE_NAMESPACE":
    case "WORKFLOW_INPUT_TYPE_MISMATCH":
      return {
        message: `Bước “${nodeLabel(issue)}” đang tham chiếu dữ liệu đầu vào không hợp lệ.`,
        suggestion:
          "Chọn lại trường đầu vào đã khai báo và kiểm tra kiểu dữ liệu.",
      };
    case "TASK_FORM_VERSION_NOT_PINNED":
    case "TASK_FORM_VERSION_INVALID":
    case "TASK_FORM_VERSION_MISSING":
    case "TASK_FORM_VERSION_NOT_PUBLISHED":
      return {
        message: `Biểu mẫu công việc của bước “${nodeLabel(issue)}” chưa được liên kết đúng.`,
        suggestion: "Chọn một phiên bản biểu mẫu đã phát hành.",
      };
    case "UNKNOWN_NODE_TYPE":
    case "NODE_TYPE_UNKNOWN":
      return {
        message: `Bước “${nodeLabel(issue)}” có loại bước chưa được hỗ trợ.`,
        suggestion: "Chọn lại loại bước từ danh mục bước.",
      };
    default:
      return {
        message: "Quy trình có một vấn đề cần được xử lý.",
        suggestion:
          "Mở bước được đánh dấu và kiểm tra lại cấu hình hoặc đường nối.",
      };
  }
}
