---
name: issue-implement
description: Implement a GitHub issue end to end in the VideoGame Platform repository, including the affected canonical documentation and the GitHub Project item status. Use when a task references an issue number or a Project item. Do not use for exploratory questions, and never use it to commit, push, or merge.
---

# Issue implement

Take one issue from `Ready` to a reviewable working tree. The owner reviews the
complete diff and performs every Git publication step.

`docs/development/work-management.md` owns the statuses and Project fields;
`docs/development/delivery-lifecycle.md` owns the change flow and Definition of Done.
This skill sequences them.

## 1. Read the issue before touching code

```bash
gh issue view <number> --json number,title,body,labels,state,url
```

Establish the outcome, in/out scope, acceptance criteria, dependencies, and highest
risk. If the issue is not `Ready` by the lifecycle definition, say what is missing and
stop rather than inventing the missing decision.

## 2. Move the Project item to `In progress`

Find the issue's item on the delivery Project and set its status before work starts.
Inspect the live board rather than assuming its shape:

```bash
gh project item-list <project-number> --owner <owner> --format json
gh project field-list <project-number> --owner <owner> --format json
```

A stale board is a defect. Update the status again when the work enters review or
returns for changes.

## 3. Implement one vertical slice

1. Read only the approved sources the change actually affects.
2. Load the area skill first: `videogame-platform-backend-development`,
   `videogame-platform-frontend-development`, `openapi-change` for a contract change,
   and `scalability-by-design` for any API, query, persistence, pagination,
   synchronization, cache, metric, batch, or large-collection path.
3. Make a focused change. Do not refactor unrelated code, and do not implement
   deferred story-map ideas because they are adjacent.
4. Keep the change reversible and the boundaries explicit.

## 4. Update documentation and versions atomically

- Identify the canonical owner in `docs/README.md` and update that document only.
- Remove text the change made obsolete instead of adding a second explanation.
- Assess Semantic Versioning impact for every affected artefact and update the
  references consistently.
- Update the tracked Postman collection whenever a backend API changed.

## 5. Validate

Use the `validate` skill to select the smallest check that can detect a regression in
what changed. Leave the complete suite to CI.

## 6. Report, then stop

Do not run `git commit`, `git push`, `git merge`, `gh pr create`, or `gh pr merge`
unless the owner asked for that step in this task. Finishing the implementation is
not an instruction to publish it.

Report:

- the issue and its acceptance criteria;
- files added, modified, and deleted, with the reason for each;
- the Project status transitions applied;
- checks executed and their results;
- Semantic Versioning impact;
- residual risk, known limitations, and anything deliberately left out of scope.

Leave the working tree ready for the owner's review of the complete diff.
