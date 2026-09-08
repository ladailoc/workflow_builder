import { describe, expect, it } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { CatalogGrid } from "./components/catalog-grid";
import type { CatalogItem } from "./types";

const MOCK_CATALOG_ITEMS: CatalogItem[] = [
  {
    id: "item-1",
    key: "laptop_procurement",
    name: "Laptop Procurement",
    description: "Request a new corporate laptop or workstation upgrade.",
    category: "IT Equipment",
  },
  {
    id: "item-2",
    key: "access_request",
    name: "System Access Grant",
    description: "Request access to internal databases, VPN, or production clusters.",
    category: "IT Access",
  },
  {
    id: "item-3",
    key: "travel_reimbursement",
    name: "Travel Expense Reimbursement",
    description: "Submit receipts for domestic or international business travel expenses.",
    category: "Finance",
  },
];

describe("CatalogGrid Component", () => {
  it("renders catalog items with business titles and descriptions", () => {
    render(<CatalogGrid items={MOCK_CATALOG_ITEMS} />);

    expect(screen.getByText("Laptop Procurement")).toBeInTheDocument();
    expect(screen.getByText("System Access Grant")).toBeInTheDocument();
    expect(screen.getByText("Travel Expense Reimbursement")).toBeInTheDocument();

    // Verify categories are rendered
    expect(screen.getAllByText("IT Equipment").length).toBeGreaterThan(0);
    expect(screen.getAllByText("IT Access").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Finance").length).toBeGreaterThan(0);
  });

  it("filters items by search input query", () => {
    render(<CatalogGrid items={MOCK_CATALOG_ITEMS} />);

    const searchInput = screen.getByTestId("catalog-search-input");
    fireEvent.change(searchInput, { target: { value: "laptop" } });

    expect(screen.getByText("Laptop Procurement")).toBeInTheDocument();
    expect(screen.queryByText("System Access Grant")).not.toBeInTheDocument();
    expect(screen.queryByText("Travel Expense Reimbursement")).not.toBeInTheDocument();
  });

  it("filters items by category pill selection", () => {
    render(<CatalogGrid items={MOCK_CATALOG_ITEMS} />);

    const financePill = screen.getByTestId("category-filter-finance");
    fireEvent.click(financePill);

    expect(screen.queryByText("Laptop Procurement")).not.toBeInTheDocument();
    expect(screen.getByText("Travel Expense Reimbursement")).toBeInTheDocument();
  });

  it("shows empty state when search produces zero matches", () => {
    render(<CatalogGrid items={MOCK_CATALOG_ITEMS} />);

    const searchInput = screen.getByTestId("catalog-search-input");
    fireEvent.change(searchInput, { target: { value: "nonexistent query" } });

    expect(screen.getByTestId("catalog-empty-state")).toBeInTheDocument();
    expect(screen.getByText("No request types found")).toBeInTheDocument();
  });
});
