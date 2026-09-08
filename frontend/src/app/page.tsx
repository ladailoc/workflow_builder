import { FeaturePlaceholder } from "@/shared/components/feature-placeholder";

const boundaries = [
  ["Request catalog", "Browse request types and begin a ticket."],
  ["Tickets", "Track business requests and revisions."],
  ["Events", "Inspect workflow runtime occurrences."],
  ["Tasks", "Work with assigned human tasks."],
  ["Workflow builder", "Design and validate workflow definitions."],
  ["Organization & admin", "Manage organization and platform access."],
  ["Operations", "Monitor failures, jobs, and recovery actions."],
] as const;

export default function Home() {
  return (
    <main className="mx-auto min-h-screen max-w-6xl px-6 py-12">
      <p className="text-sm font-semibold tracking-wide text-blue-700 uppercase">
        Bootstrap
      </p>
      <h1 className="mt-2 text-4xl font-semibold tracking-tight">
        Workflow Platform
      </h1>
      <p className="mt-4 max-w-2xl text-slate-600">
        The application shell and feature boundaries are ready. Business
        capabilities will be added one prompt at a time.
      </p>
      <section className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {boundaries.map(([title, description]) => (
          <FeaturePlaceholder
            key={title}
            title={title}
            description={description}
          />
        ))}
      </section>
    </main>
  );
}
