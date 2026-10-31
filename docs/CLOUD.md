# Cloud (Hub → Cloud)

Everything here is optional, runs on the user's own accounts, and is off until set up. The
nightly job runs at 23:40 (and on **RUN NOW**).

| Feature | Where the data goes | Needs |
|---|---|---|
| Automatic backup | A folder you pick (Google Drive, Dropbox, the phone): 7 rolling files, one per weekday | nothing else |
| GitHub backup | `diligent-backup.json` in a private repo; the repo history keeps every night | fine-grained token with *Contents: write* on that repo |
| Sync, web dashboard, laptop extension, rooms, challenges | Your Supabase project | project URL + anon key, and the SQL below |
| Nightly export | A webhook (Zapier, Make, Google Apps Script) and/or a Notion database | webhook URL; Notion integration token + database id |

Tokens and keys are stored encrypted with the Android Keystore (`SecretStore`).

## Supabase set-up

1. Create a free project at supabase.com.
2. In the SQL editor, run the script below.
3. In Diligent → Hub → Cloud, paste the project URL and the **anon** key, and pick a name.

Your rows are scoped by a random **owner id** the app makes on first use (Hub → Cloud shows
it). Requests carry it in an `x-diligent-owner` header, and the row policies only return rows
whose `owner` matches. Treat the owner id like a password: with the URL and anon key it reads
your synced days. Rooms and challenges are scoped by their code in `x-diligent-room` the
same way, and members appear under a one-way id derived from the owner id.

```sql
create table if not exists diligent_days (
  owner text not null, date text not null, activity text not null,
  unit text, progress double precision, goal double precision, completed boolean,
  updated_at bigint, primary key (owner, date, activity));

create table if not exists diligent_desktop (
  owner text not null, date text not null, activity text not null,
  minutes double precision, updated_at bigint, primary key (owner, date, activity));

create table if not exists diligent_presence (
  room text not null, member text not null, name text, activity text,
  started_at bigint, until_at bigint, broken boolean, updated_at bigint,
  primary key (room, member));

create table if not exists diligent_challenges (
  code text primary key, title text, keyword text, target double precision,
  unit text, start_date text, end_date text);

create table if not exists diligent_scores (
  code text not null, member text not null, name text, total double precision,
  updated_at bigint, primary key (code, member));

create or replace function diligent_header(name text) returns text
  language sql stable as $$
    select coalesce(current_setting('request.headers', true)::json ->> name, '')
  $$;

alter table diligent_days enable row level security;
alter table diligent_desktop enable row level security;
alter table diligent_presence enable row level security;
alter table diligent_challenges enable row level security;
alter table diligent_scores enable row level security;

create policy own_days on diligent_days for all
  using (owner = diligent_header('x-diligent-owner'))
  with check (owner = diligent_header('x-diligent-owner'));
create policy own_desktop on diligent_desktop for all
  using (owner = diligent_header('x-diligent-owner'))
  with check (owner = diligent_header('x-diligent-owner'));
create policy room_presence on diligent_presence for all
  using (room = diligent_header('x-diligent-room'))
  with check (room = diligent_header('x-diligent-room'));
create policy room_challenges on diligent_challenges for all
  using (code = diligent_header('x-diligent-room'))
  with check (code = diligent_header('x-diligent-room'));
create policy room_scores on diligent_scores for all
  using (code = diligent_header('x-diligent-room'))
  with check (code = diligent_header('x-diligent-room'));
```

## What syncs

- **Days:** the last 35 days of progress go up each sync; changes another device made since the
  last pull come down. Activities match **by name**, so give them the same names on each device.
  The newer edit wins.
- **Laptop:** minutes the browser extension (`companion/extension`) logged today raise the
  matching activities (hrs or mins).
- **Rooms:** your status is pushed when the focus timer starts or stops and every 20 s while
  the Study room screen is open. Nothing polls in the background.
- **Challenges:** your total (activities whose name contains the keyword, between the dates) is
  pushed when you open Challenges and nightly.

## Web dashboard

Open `companion/web/index.html` in any browser (or host it on GitHub Pages). Enter the project
URL, anon key and owner id; it keeps them in that browser only and shows your heatmap and
totals.

## Laptop extension

`companion/extension` is a Chrome / Edge extension (Manifest V3). Load it with
*Extensions → Developer mode → Load unpacked*. In its options, set the Supabase details and map
sites to activities, e.g. `github.com = Coding`, `leetcode.com = LeetCode`,
`arxiv.org = Research Papers`. It counts time on the focused tab while you're active, and
uploads per-activity minutes every 5 minutes.

## Nightly export

Yesterday goes out once, after midnight:

```json
{"date": "2026-10-30", "done": 5, "total": 7, "hours": 6.5, "mood": 4, "energy": 3,
 "sleep_minutes": 420, "note": "…",
 "activities": [{"name": "Quantum Computing", "unit": "hrs", "progress": 3.5, "goal": 5, "done": false}]}
```

For Notion, share the database with your integration; it needs the properties **Name**
(title), **Date** (date), **Done** (number), **Hours** (number) and **Note** (text).
