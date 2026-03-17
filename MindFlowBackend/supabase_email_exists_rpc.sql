-- Run in Supabase SQL Editor once.
-- Purpose: provide a reliable email existence check against auth.users for forgot-password flow.

create or replace function public.email_exists(p_email text)
returns boolean
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_exists boolean;
begin
  select exists (
    select 1
    from auth.users u
    where lower(u.email) = lower(trim(p_email))
  ) into v_exists;

  return coalesce(v_exists, false);
end;
$$;

revoke all on function public.email_exists(text) from public;
grant execute on function public.email_exists(text) to anon;
grant execute on function public.email_exists(text) to authenticated;
