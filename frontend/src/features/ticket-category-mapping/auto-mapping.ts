export interface MappingTypeDescriptor {
  type?: string;
  itemType?: string | MappingTypeDescriptor | null;
}

export interface MappingTarget {
  id: string;
  key: string;
  label?: string | null;
  semanticTag?: string | null;
  type: string | MappingTypeDescriptor;
  ordinal?: number;
}

export interface MappingSource {
  id: string;
  key: string;
  label?: string | null;
  semanticTag?: string | null;
  type: string | MappingTypeDescriptor;
  ordinal?: number;
}

export type AutoMatchReason = "SEMANTIC" | "KEY" | "LABEL" | "TYPE";

export interface AutomaticMapping {
  targetId: string;
  sourceId: string;
  reason: AutoMatchReason;
  score: number;
}

function normalized(value: string | null | undefined): string {
  return (value ?? "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[đĐ]/g, "d")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "")
    .trim();
}

function tokens(value: string | null | undefined): Set<string> {
  return new Set(
    (value ?? "")
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/[đĐ]/g, "d")
      .toLowerCase()
      .split(/[^a-z0-9]+/)
      .filter((token) => token.length >= 2),
  );
}

function descriptorType(type: string | MappingTypeDescriptor): string {
  const raw = typeof type === "string" ? type : (type.type ?? "");
  const normalizedType = raw.toUpperCase();
  if (normalizedType === "DECIMAL" || normalizedType === "NUMBER") {
    return "NUMBER";
  }
  if (
    normalizedType === "FILE" ||
    normalizedType === "FILE_REF" ||
    normalizedType === "FILE_LIST"
  ) {
    return "FILE";
  }
  if (normalizedType === "TEXTAREA") return "STRING";
  if (normalizedType === "ARRAY") {
    const itemType =
      typeof type === "string" || !type.itemType
        ? ""
        : descriptorType(type.itemType);
    return `ARRAY:${itemType}`;
  }
  return normalizedType;
}

function compatible(target: MappingTarget, source: MappingSource): boolean {
  const targetType = descriptorType(target.type);
  const sourceType = descriptorType(source.type);
  if (targetType === sourceType) return true;
  return targetType === "NUMBER" && sourceType === "INTEGER";
}

function candidateScore(
  target: MappingTarget,
  source: MappingSource,
): { score: number; reason: AutoMatchReason } | null {
  if (!compatible(target, source)) return null;

  const targetKey = normalized(target.key);
  const sourceKey = normalized(source.key);
  const targetLabel = normalized(target.label);
  const sourceLabel = normalized(source.label);
  const targetSemantic = normalized(target.semanticTag);
  const sourceSemantic = normalized(source.semanticTag);

  if (targetSemantic && targetSemantic === sourceSemantic) {
    return { score: 120, reason: "SEMANTIC" };
  }
  if (targetKey && targetKey === sourceKey) {
    return { score: 110, reason: "KEY" };
  }
  if (targetLabel && targetLabel === sourceLabel) {
    return { score: 100, reason: "LABEL" };
  }

  const targetTokens = tokens(`${target.key} ${target.label ?? ""}`);
  const sourceTokens = tokens(`${source.key} ${source.label ?? ""}`);
  const sharedTokens = [...targetTokens].filter((token) =>
    sourceTokens.has(token),
  );
  if (sharedTokens.length > 0) {
    return {
      score: 70 + Math.min(sharedTokens.length, 3) * 5,
      reason: "LABEL",
    };
  }

  return { score: 40, reason: "TYPE" };
}

/**
 * Suggests deterministic one-to-one mappings. Name/semantic matches win over
 * type-only matches; an ambiguous type-only match is intentionally left for
 * the user to choose manually.
 */
export function suggestAutomaticMappings(
  targets: MappingTarget[],
  sources: MappingSource[],
): AutomaticMapping[] {
  const remainingTargets = new Map(
    targets.map((target) => [target.id, target]),
  );
  const remainingSources = new Map(
    sources.map((source) => [source.id, source]),
  );
  const assignments: AutomaticMapping[] = [];

  const targetOrder = [...targets].sort(
    (left, right) => (left.ordinal ?? 0) - (right.ordinal ?? 0),
  );
  const sourceOrder = [...sources].sort(
    (left, right) => (left.ordinal ?? 0) - (right.ordinal ?? 0),
  );

  while (remainingTargets.size > 0 && remainingSources.size > 0) {
    const candidates = targetOrder.flatMap((target) => {
      if (!remainingTargets.has(target.id)) return [];
      return sourceOrder.flatMap((source) => {
        if (!remainingSources.has(source.id)) return [];
        const match = candidateScore(target, source);
        return match ? [{ target, source, ...match }] : [];
      });
    });

    const strongCandidates = candidates
      .filter((candidate) => candidate.score > 40)
      .sort(
        (left, right) =>
          right.score - left.score ||
          (left.target.ordinal ?? 0) - (right.target.ordinal ?? 0) ||
          (left.source.ordinal ?? 0) - (right.source.ordinal ?? 0),
      );
    const bestStrong = strongCandidates[0];

    if (bestStrong) {
      remainingTargets.delete(bestStrong.target.id);
      remainingSources.delete(bestStrong.source.id);
      assignments.push({
        targetId: bestStrong.target.id,
        sourceId: bestStrong.source.id,
        reason: bestStrong.reason,
        score: bestStrong.score,
      });
      continue;
    }

    const typeCandidatesByTarget = new Map<string, typeof candidates>();
    candidates.forEach((candidate) => {
      const current = typeCandidatesByTarget.get(candidate.target.id) ?? [];
      current.push(candidate);
      typeCandidatesByTarget.set(candidate.target.id, current);
    });
    const sourceCandidateCount = new Map<string, number>();
    candidates.forEach((candidate) => {
      sourceCandidateCount.set(
        candidate.source.id,
        (sourceCandidateCount.get(candidate.source.id) ?? 0) + 1,
      );
    });
    const uniqueTypeMatch = targetOrder
      .filter((target) => remainingTargets.has(target.id))
      .map((target) => typeCandidatesByTarget.get(target.id) ?? [])
      .find(
        (targetCandidates) =>
          targetCandidates.length === 1 &&
          (sourceCandidateCount.get(targetCandidates[0].source.id) ?? 0) === 1,
      )?.[0];

    if (!uniqueTypeMatch) break;
    remainingTargets.delete(uniqueTypeMatch.target.id);
    remainingSources.delete(uniqueTypeMatch.source.id);
    assignments.push({
      targetId: uniqueTypeMatch.target.id,
      sourceId: uniqueTypeMatch.source.id,
      reason: uniqueTypeMatch.reason,
      score: uniqueTypeMatch.score,
    });
  }

  return assignments.sort(
    (left, right) =>
      (targets.find((target) => target.id === left.targetId)?.ordinal ?? 0) -
      (targets.find((target) => target.id === right.targetId)?.ordinal ?? 0),
  );
}
