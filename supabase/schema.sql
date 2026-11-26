-- Run this in the Supabase SQL Editor.
create table if not exists public.admin_credentials (
  id integer primary key check (id = 1),
  password_hash text not null,
  must_change boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.app_settings (
  key text primary key,
  value jsonb not null,
  updated_at timestamptz not null default now()
);

-- The backend uses the service-role key and bypasses RLS. Deny direct client access.
alter table public.admin_credentials enable row level security;
alter table public.app_settings enable row level security;

-- Public app settings are exposed only through the API; do not add anon read policies.
