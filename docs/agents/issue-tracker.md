# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

Infer the repo from `git remote -v` — `gh` does this automatically when run inside a clone.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

- **Map**: a GitHub issue labelled `wayfinder:map`.
- **Ticket**: a native GitHub sub-issue of the map, labelled `wayfinder:<type>`.
  - Create the map issue first, then create each ticket as a normal issue, then attach it as a sub-issue via GraphQL:
    ```
    gh api graphql -f query='mutation { addSubIssue(input: { issueId: "<map node id>", subIssueId: "<ticket node id>" }) { issue { id } } }'
    ```
  - Get an issue's GraphQL node id: `gh issue view <number> --json id -q .id` (or via `gh api graphql` query on `repository.issue.id`).
- **Blocking**: native issue dependency, via GraphQL:
  ```
  gh api graphql -f query='mutation { addBlockedBy(input: { issueId: "<blocked ticket node id>", blockingIssueId: "<blocking ticket node id>" }) { issue { id } } }'
  ```
  This renders natively in the GitHub UI as "Blocked by #N".
- **Frontier query**: open, unassigned child issues of the map with no open blockers. List the map's sub-issues and filter:
  ```
  gh issue list --state open --json number,title,assignees,labels --search "repo:<owner>/<repo>"
  ```
  then cross-reference against the map's sub-issue list and each ticket's blocking state (`gh issue view <n> --json ... ` doesn't surface dependencies directly — check via the GraphQL `issue.blockedBy` connection, or visually in the GitHub UI's "Development"/issue sidebar).
- **Claim a ticket**: `gh issue edit <number> --add-assignee "@me"`.
