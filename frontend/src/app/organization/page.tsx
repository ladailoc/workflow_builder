"use client";

import { useEffect, useState } from "react";
import { AuthRouteGuard } from "@/features/auth";
import { apiGet } from "@/shared/api/client";
import { ErrorState } from "@/shared/components/ui/error-state";
import { LoadingState } from "@/shared/components/ui/loading-state";

interface DirectoryUnit {
  id: string;
  unitCode: string;
  name: string;
  unitType: string;
  parentUnitId?: string | null;
  managerPositionId?: string | null;
  status: string;
}

interface DirectoryPosition {
  id: string;
  positionCode: string;
  title: string;
  orgUnitId: string;
  reportsToPositionId?: string | null;
  headOfUnit: boolean;
  level: number;
  status: string;
  activeEmployeeIds: string[];
}

interface DirectoryEmployee {
  id: string;
  userId: string;
  employeeCode: string;
  fullName: string;
  email: string;
  status: string;
  primaryPositionId?: string | null;
  orgUnitId?: string | null;
  managerUserId?: string | null;
}

interface DirectorySnapshot {
  units: DirectoryUnit[];
  positions: DirectoryPosition[];
  employees: DirectoryEmployee[];
  unitCount: number;
  positionCount: number;
  employeeCount: number;
}

type UnitTreeNode = DirectoryUnit & { children: UnitTreeNode[] };
type PositionTreeNode = DirectoryPosition & { children: PositionTreeNode[] };

function sortTree<T extends { name?: string; title?: string }>(nodes: T[]) {
  nodes.sort((a, b) => (a.name ?? a.title ?? "").localeCompare(b.name ?? b.title ?? "", "vi"));
  return nodes;
}

function buildUnitTree(units: DirectoryUnit[]): UnitTreeNode[] {
  const nodes = new Map(units.map((unit) => [unit.id, { ...unit, children: [] as UnitTreeNode[] }]));
  const roots: UnitTreeNode[] = [];
  const attached = new Set<string>();

  for (const unit of units) {
    const node = nodes.get(unit.id);
    const parent = unit.parentUnitId ? nodes.get(unit.parentUnitId) : undefined;
    if (node && parent && parent.id !== node.id) {
      parent.children.push(node);
      attached.add(node.id);
    } else if (node) {
      roots.push(node);
    }
  }

  if (roots.length === 0 && units.length > 0) {
    const fallback = [...nodes.values()].sort((a, b) => a.name.localeCompare(b.name, "vi"))[0];
    if (fallback) roots.push(fallback);
  }

  const knownRootIds = new Set(roots.map((root) => root.id));
  for (const node of nodes.values()) {
    if (!attached.has(node.id) && !knownRootIds.has(node.id)) roots.push(node);
  }

  const sortChildren = (node: UnitTreeNode) => {
    sortTree(node.children);
    node.children.forEach(sortChildren);
  };
  sortTree(roots);
  roots.forEach(sortChildren);
  return roots;
}

function buildPositionTree(positions: DirectoryPosition[]): PositionTreeNode[] {
  const nodes = new Map(positions.map((position) => [position.id, { ...position, children: [] as PositionTreeNode[] }]));
  const roots: PositionTreeNode[] = [];
  const attached = new Set<string>();

  for (const position of positions) {
    const node = nodes.get(position.id);
    const parent = position.reportsToPositionId ? nodes.get(position.reportsToPositionId) : undefined;
    if (node && parent && parent.id !== node.id) {
      parent.children.push(node);
      attached.add(node.id);
    } else if (node) {
      roots.push(node);
    }
  }

  if (roots.length === 0 && positions.length > 0) {
    const fallback = [...nodes.values()].sort((a, b) => a.level - b.level || a.title.localeCompare(b.title, "vi"))[0];
    if (fallback) roots.push(fallback);
  }

  const knownRootIds = new Set(roots.map((root) => root.id));
  for (const node of nodes.values()) {
    if (!attached.has(node.id) && !knownRootIds.has(node.id)) roots.push(node);
  }

  const sortChildren = (node: PositionTreeNode) => {
    node.children.sort((a, b) => a.level - b.level || a.title.localeCompare(b.title, "vi"));
    node.children.forEach(sortChildren);
  };
  roots.sort((a, b) => a.level - b.level || a.title.localeCompare(b.title, "vi"));
  roots.forEach(sortChildren);
  return roots;
}

function positionHolders(position: DirectoryPosition | undefined, employees: Map<string, DirectoryEmployee>) {
  const names = position?.activeEmployeeIds
    .map((employeeId) => employees.get(employeeId)?.fullName)
    .filter((name): name is string => Boolean(name));
  return names && names.length > 0 ? names.join(", ") : "Chưa phân công";
}

function UnitTreeNodeView({
  node,
  units,
  positions,
  employees,
  ancestors = [],
}: {
  node: UnitTreeNode;
  units: Map<string, DirectoryUnit>;
  positions: Map<string, DirectoryPosition>;
  employees: Map<string, DirectoryEmployee>;
  ancestors?: string[];
}) {
  if (ancestors.includes(node.id)) {
    return <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">Phát hiện vòng lặp đơn vị.</p>;
  }
  const manager = node.managerPositionId
    ? positions.get(node.managerPositionId)
    : [...positions.values()].find((position) => position.orgUnitId === node.id && position.headOfUnit);
  const parent = node.parentUnitId ? units.get(node.parentUnitId) : undefined;
  const nextAncestors = [...ancestors, node.id];

  return (
    <div className="space-y-2">
      <article className="rounded-lg border border-slate-200 bg-white px-3 py-3 shadow-xs transition-colors hover:border-blue-200">
        <div className="flex items-start gap-3">
          <span className="mt-0.5 flex h-6 min-w-6 items-center justify-center rounded-md bg-blue-50 px-1.5 text-[10px] font-bold text-blue-700">C{ancestors.length + 1}</span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="truncate text-sm font-semibold text-slate-900">{node.name}</h3>
              <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-slate-500">{node.unitCode}</span>
              <span className="text-[10px] uppercase tracking-wide text-slate-400">{node.unitType}</span>
            </div>
            <div className="mt-2 grid gap-x-4 gap-y-1 text-xs text-slate-600 sm:grid-cols-2">
              <p><span className="font-semibold text-slate-800">Cấp trên:</span> {parent?.name ?? "Gốc tổ chức"}</p>
              <p><span className="font-semibold text-slate-800">Quản lý:</span> {manager?.title ?? "Chưa gán vị trí"}{manager ? <span className="text-slate-500"> · {positionHolders(manager, employees)}</span> : null}</p>
            </div>
          </div>
          <span className={`shrink-0 rounded-full px-2 py-1 text-[10px] font-semibold ${node.status === "ACTIVE" ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"}`}>
            {node.status}
          </span>
        </div>
      </article>
      {node.children.length > 0 ? (
        <div className="space-y-2 border-l-2 border-blue-100 pl-3 sm:ml-5 sm:pl-4">
          {node.children.map((child) => (
            <UnitTreeNodeView key={child.id} node={child} units={units} positions={positions} employees={employees} ancestors={nextAncestors} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function ReportingTreeNodeView({
  node,
  units,
  employees,
  positions,
  ancestors = [],
  parent,
}: {
  node: PositionTreeNode;
  units: Map<string, DirectoryUnit>;
  employees: Map<string, DirectoryEmployee>;
  positions: Map<string, DirectoryPosition>;
  ancestors?: string[];
  parent?: PositionTreeNode;
}) {
  if (ancestors.includes(node.id)) {
    return <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">Phát hiện vòng lặp reporting.</p>;
  }
  const unit = units.get(node.orgUnitId);
  const nextAncestors = [...ancestors, node.id];
  const parentFromMap = node.reportsToPositionId ? positions.get(node.reportsToPositionId) : undefined;
  const parentPosition = parent ?? parentFromMap;

  return (
    <div className="space-y-2">
      <article className="rounded-lg border border-slate-200 bg-white px-3 py-3 shadow-xs transition-colors hover:border-blue-200">
        <div className="flex items-start gap-3">
          <span className="mt-0.5 flex h-6 min-w-6 items-center justify-center rounded-md bg-blue-50 px-1.5 text-[10px] font-bold text-blue-700">L{node.level}</span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="truncate text-sm font-semibold text-slate-900">{node.title}</h3>
              {node.headOfUnit ? <span className="rounded-full bg-violet-50 px-2 py-0.5 text-[10px] font-semibold text-violet-700">Trưởng đơn vị</span> : null}
              <span className="font-mono text-[10px] text-slate-400">{node.positionCode}</span>
            </div>
            <div className="mt-2 grid gap-x-4 gap-y-1 text-xs text-slate-600 sm:grid-cols-2">
              <p><span className="font-semibold text-slate-800">Đơn vị:</span> {unit?.name ?? "Chưa xác định"}</p>
              <p><span className="font-semibold text-slate-800">Người giữ:</span> {positionHolders(node, employees)}</p>
              <p className="sm:col-span-2">
                <span className="font-semibold text-slate-800">Báo cáo lên:</span>{" "}
                {parentPosition ? (
                  <>
                    {parentPosition.title}{units.get(parentPosition.orgUnitId) ? ` · ${units.get(parentPosition.orgUnitId)?.name}` : ""}
                    <span className="text-slate-500"> · {positionHolders(parentPosition, employees)}</span>
                  </>
                ) : "Cấp cao nhất"}
              </p>
            </div>
          </div>
          <span className={`shrink-0 rounded-full px-2 py-1 text-[10px] font-semibold ${node.status === "ACTIVE" ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"}`}>
            {node.status}
          </span>
        </div>
      </article>
      {node.children.length > 0 ? (
        <div className="space-y-2 border-l-2 border-blue-100 pl-3 sm:ml-5 sm:pl-4">
          {node.children.map((child) => (
            <ReportingTreeNodeView key={child.id} node={child} units={units} employees={employees} positions={positions} ancestors={nextAncestors} parent={node} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

export default function OrganizationPage() {
  const [directory, setDirectory] = useState<DirectorySnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hierarchyView, setHierarchyView] = useState<"reporting" | "units">("reporting");

  const loadDirectory = () => {
    setLoading(true);
    setError(null);
    apiGet<DirectorySnapshot>("/api/v1/organization/directory")
      .then(setDirectory)
      .catch((reason: unknown) => {
        setError(reason instanceof Error ? reason.message : "Không thể tải danh bạ tổ chức.");
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    void Promise.resolve().then(() => loadDirectory());
  }, []);

  const unitTree = directory ? buildUnitTree(directory.units) : [];
  const reportingTree = directory ? buildPositionTree(directory.positions) : [];
  const unitsById = new Map((directory?.units ?? []).map((unit) => [unit.id, unit]));
  const positionsById = new Map((directory?.positions ?? []).map((position) => [position.id, position]));
  const employeesById = new Map((directory?.employees ?? []).map((employee) => [employee.id, employee]));

  return (
    <AuthRouteGuard roles={["ADMIN", "WORKFLOW_OWNER"]}>
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Tổ chức & quản trị
          </h1>
        </div>
        {loading ? <LoadingState title="Đang tải danh bạ tổ chức…" /> : null}
        {!loading && error ? (
          <ErrorState title="Không thể tải danh bạ tổ chức" message={error} onRetry={loadDirectory} />
        ) : null}
        {!loading && !error && directory ? (
          <div data-testid="organization-container" className="space-y-5">
            <div className="grid gap-4 sm:grid-cols-3">
              {[
                ["Đơn vị", directory.unitCount],
                ["Vị trí", directory.positionCount],
                ["Nhân sự", directory.employeeCount],
              ].map(([label, count]) => (
                <div key={label} className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
                  <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</p>
                  <p className="mt-2 text-3xl font-bold text-slate-900">{count}</p>
                </div>
              ))}
            </div>
            <section data-testid="organization-hierarchy" className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
                <h2 className="text-sm font-semibold text-slate-900">Sơ đồ phân cấp tổ chức</h2>
                <div role="tablist" aria-label="Chọn sơ đồ phân cấp" className="inline-flex rounded-lg bg-slate-100 p-1">
                  <button
                    type="button"
                    role="tab"
                    aria-selected={hierarchyView === "reporting"}
                    data-testid="organization-hierarchy-toggle-reporting"
                    onClick={() => setHierarchyView("reporting")}
                    className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${hierarchyView === "reporting" ? "bg-white text-slate-900 shadow-xs" : "text-slate-500 hover:text-slate-800"}`}
                  >
                    Tuyến báo cáo <span className="ml-1 text-[10px] text-slate-400">{directory.positionCount}</span>
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={hierarchyView === "units"}
                    data-testid="organization-hierarchy-toggle-units"
                    onClick={() => setHierarchyView("units")}
                    className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${hierarchyView === "units" ? "bg-white text-slate-900 shadow-xs" : "text-slate-500 hover:text-slate-800"}`}
                  >
                    Đơn vị <span className="ml-1 text-[10px] text-slate-400">{directory.unitCount}</span>
                  </button>
                </div>
              </div>
              <div className="bg-slate-50/60 p-4 sm:p-5">
                {hierarchyView === "units" ? (
                  unitTree.length === 0 ? (
                    <p className="rounded-lg border border-dashed border-slate-300 bg-white p-5 text-sm text-slate-500">Chưa có đơn vị tổ chức.</p>
                  ) : (
                    <div className="space-y-3" role="tabpanel" aria-label="Cây đơn vị">
                      {unitTree.map((unit) => <UnitTreeNodeView key={unit.id} node={unit} units={unitsById} positions={positionsById} employees={employeesById} />)}
                    </div>
                  )
                ) : (
                  reportingTree.length === 0 ? (
                    <p className="rounded-lg border border-dashed border-slate-300 bg-white p-5 text-sm text-slate-500">Chưa có vị trí báo cáo.</p>
                  ) : (
                    <div className="space-y-3" role="tabpanel" aria-label="Cây báo cáo theo vị trí">
                      {reportingTree.map((position) => <ReportingTreeNodeView key={position.id} node={position} units={unitsById} employees={employeesById} positions={positionsById} />)}
                    </div>
                  )
                )}
              </div>
            </section>
            <div className="grid gap-5 xl:grid-cols-2">
              <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
                <div className="border-b border-slate-100 px-5 py-4">
                  <h2 className="text-sm font-semibold text-slate-900">Danh sách đơn vị</h2>
                </div>
                <div className="max-h-[28rem] overflow-auto">
                  {directory.units.length === 0 ? (
                    <p className="p-6 text-sm text-slate-500">Chưa có đơn vị tổ chức.</p>
                  ) : (
                    <table className="w-full text-left text-sm">
                      <thead className="sticky top-0 border-b border-slate-100 bg-slate-50 text-[11px] uppercase tracking-wide text-slate-500">
                        <tr><th className="px-5 py-3">Đơn vị</th><th className="px-5 py-3">Loại</th><th className="px-5 py-3">Trạng thái</th></tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {directory.units.map((unit) => (
                          <tr key={unit.id} className="hover:bg-slate-50">
                            <td className="px-5 py-3"><p className="font-semibold text-slate-800">{unit.name}</p><p className="font-mono text-[11px] text-slate-400">{unit.unitCode}</p></td>
                            <td className="px-5 py-3 text-xs text-slate-600">{unit.unitType}</td>
                            <td className="px-5 py-3"><span className={`rounded-full px-2 py-1 text-[10px] font-semibold ${unit.status === "ACTIVE" ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"}`}>{unit.status}</span></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </section>
              <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
                <div className="border-b border-slate-100 px-5 py-4">
                  <h2 className="text-sm font-semibold text-slate-900">Danh sách vị trí</h2>
                </div>
                <div className="max-h-[28rem] overflow-auto">
                  {directory.positions.length === 0 ? (
                    <p className="p-6 text-sm text-slate-500">Chưa có vị trí.</p>
                  ) : (
                    <table className="w-full text-left text-sm">
                      <thead className="sticky top-0 border-b border-slate-100 bg-slate-50 text-[11px] uppercase tracking-wide text-slate-500">
                        <tr><th className="px-5 py-3">Vị trí</th><th className="px-5 py-3">Cấp</th><th className="px-5 py-3">Người giữ</th></tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {directory.positions.map((position) => (
                          <tr key={position.id} className="hover:bg-slate-50">
                            <td className="px-5 py-3"><p className="font-semibold text-slate-800">{position.title}{position.headOfUnit ? " · Head" : ""}</p><p className="font-mono text-[11px] text-slate-400">{position.positionCode}</p></td>
                            <td className="px-5 py-3 text-xs text-slate-600">L{position.level}</td>
                            <td className="px-5 py-3 text-xs text-slate-600">{position.activeEmployeeIds.length || "Trống"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </section>
            </div>
            <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
              <div className="border-b border-slate-100 px-5 py-4"><h2 className="text-sm font-semibold text-slate-900">Danh bạ nhân viên</h2></div>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[820px] text-left text-sm">
                  <thead className="border-b border-slate-100 bg-slate-50 text-[11px] uppercase tracking-wide text-slate-500"><tr><th className="px-5 py-3">Nhân viên</th><th className="px-5 py-3">Mã</th><th className="px-5 py-3">Email</th><th className="px-5 py-3">Quản lý</th><th className="px-5 py-3">Trạng thái</th></tr></thead>
                  <tbody className="divide-y divide-slate-100">
                    {directory.employees.map((employee) => <tr key={employee.id} className="hover:bg-slate-50"><td className="px-5 py-3 font-semibold text-slate-800">{employee.fullName}</td><td className="px-5 py-3 font-mono text-xs text-slate-500">{employee.employeeCode}</td><td className="px-5 py-3 text-xs text-slate-600">{employee.email}</td><td className="px-5 py-3 font-mono text-[11px] text-slate-500">{employee.managerUserId ? `${employee.managerUserId.slice(0, 8)}…` : "Chưa gán"}</td><td className="px-5 py-3"><span className="text-xs text-slate-600">{employee.status}</span></td></tr>)}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        ) : null}
      </div>
    </AuthRouteGuard>
  );
}
