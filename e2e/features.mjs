import { chromium, request } from 'playwright';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import { mkdirSync } from 'node:fs';
const BASE = process.env.BASE_URL ?? 'http://localhost:8088';
const SHOTS = process.argv[2] ?? 'e2e/screenshots';
mkdirSync(SHOTS, { recursive: true });
const out = (n) => `${SHOTS}/${n}.png`;
const errors = [];
const ok = (m) => console.log('OK  ', m);
const sql = (q) => execSync(`docker compose exec -T db psql -U booking -d booking -qtAc "${q}"`).toString();

sql("delete from bookings where title like 'E2E%' or title like 'Block E2E%'");
const tz = 'Asia/Ho_Chi_Minh';
const today = new Date().toLocaleDateString('en-CA', { timeZone: tz });
const addDays = (d, n) => { const t = new Date(d + 'T12:00:00Z'); t.setUTCDate(t.getUTCDate() + n); return t.toISOString().slice(0, 10); };

const api = await request.newContext({ baseURL: BASE });
async function token(email, pw) {
  const r = await api.post('/api/v1/auth/login', { data: { email, password: pw } });
  return (await r.json()).accessToken;
}
const H = (t) => ({ Authorization: `Bearer ${t}` });
const rooms = (await (await api.get('/api/v1/rooms?size=100', { headers: H(await token('alice@company.local', 'Password123!')) })).json()).content;
const room = (n) => rooms.find((r) => r.name === n);
const bobTok = await token('bob@company.local', 'Password123!');

const browser = await chromium.launch();
async function login(email, pw, viewport = { width: 1360, height: 900 }) {
  const ctx = await browser.newContext({ viewport, acceptDownloads: true });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error' && !/409|422/.test(m.text())) errors.push(`${email}: ${m.text()}`); });
  page.on('pageerror', (e) => errors.push(`${email} pageerror: ${e.message}`));
  await page.goto(BASE + '/');
  await page.getByLabel(/^(Email)$/).fill(email);
  await page.locator('input[autocomplete="current-password"]').fill(pw);
  await page.locator('form button[type=submit]').click();
  await page.waitForSelector('.topbar');
  return { ctx, page };
}

// ---------- 1. language: English by default, switch to Vietnamese, persists
{
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 860 }, locale: 'vi-VN' }); // a Vietnamese browser must STILL get English
  const p = await ctx.newPage();
  await p.goto(BASE + '/');
  await p.waitForSelector('h1');
  console.log('default heading:', await p.locator('h1').innerText());
  if ((await p.locator('h1').innerText()) !== 'Sign in') throw new Error('default language must be English');
  await p.getByRole('button', { name: 'VI', exact: true }).click();
  await p.waitForSelector('text=Đăng nhập');
  await p.screenshot({ path: out('20-login-vi') });
  await p.reload();
  await p.waitForSelector('text=Đăng nhập');
  ok('English default (even for vi browser), switch to VI persists after reload');
  await p.getByLabel('Mật khẩu', { exact: true }).fill('sai-mat-khau');
  await p.getByLabel('Email', { exact: true }).fill('alice@company.local');
  await p.locator('form button[type=submit]').click();
  await p.waitForSelector('text=Email hoặc mật khẩu không đúng.');
  ok('login error is translated from the server error code');
  await ctx.close();
}

// ---------- 2. alice: attendees validation + weekly series + conflict/skip
const alice = await login('alice@company.local', 'Password123!');
let p = alice.page;
const D = addDays(today, 2);
const halong = room('Halong'), sakura = room('Sakura'); // 6 and 4 seats
// bob occupies Halong one week after D at 10:00 (to clash with the 2nd occurrence)
await api.post('/api/v1/bookings', { headers: H(bobTok), data: {
  roomId: halong.id, title: 'E2E bob clash', attendees: 2,
  startTime: `${addDays(D, 7)}T10:00:00+07:00`, endTime: `${addDays(D, 7)}T11:00:00+07:00` } });

await p.getByRole('button', { name: 'New booking' }).click();
let dlg = p.locator('dialog[open]');
await dlg.getByLabel('Title', { exact: true }).fill('E2E weekly sync');
await dlg.getByLabel('Room', { exact: true }).selectOption(halong.id);
await dlg.getByLabel('Date', { exact: true }).fill(D);
await dlg.getByLabel('Starts', { exact: true }).selectOption('10:00');
await dlg.getByLabel('Ends', { exact: true }).selectOption('11:00');
await dlg.locator('button[type=submit]').click();
await dlg.getByText('Enter the number of attendees').waitFor();
ok('attendees is required');
await dlg.getByLabel('Attendees', { exact: true }).fill('9');
await dlg.locator('button[type=submit]').click();
await dlg.getByText('Halong seats 6').waitFor();
await p.screenshot({ path: out('21-over-capacity') });
ok('over-capacity blocked client-side');
await dlg.getByLabel('Attendees', { exact: true }).fill('5');
await dlg.getByLabel('Repeat', { exact: true }).selectOption('WEEKLY');
await dlg.getByLabel('Number of meetings').fill('3');
await dlg.getByText(/3 meetings, the last on/).waitFor();
await p.screenshot({ path: out('22-repeat-form') });
await dlg.getByRole('button', { name: 'Book 3 meetings' }).click();
await dlg.getByText(/of 3 dates are already taken/).waitFor();
await p.screenshot({ path: out('23-series-conflict') });
ok('series conflict reported with the clashing date');
await dlg.getByRole('button', { name: 'Skip those and book the rest' }).click();
await p.getByText('2 bookings created, 1 skipped (room busy)').waitFor();
ok('skip policy creates the free occurrences');

// ---------- 3. my bookings: recurring badge, ics, cancel this and following
await p.goto(BASE + '/my');
await p.waitForSelector('.booking-row');
console.log('recurring badges:', await p.locator('.badge:has-text("Recurring")').count());
await p.screenshot({ path: out('24-my-recurring') });
await p.locator('.booking-row', { hasText: 'E2E weekly sync' }).first().getByRole('button', { name: 'Manage' }).click();
const [dl] = await Promise.all([p.waitForEvent('download'), p.getByRole('button', { name: 'Add to calendar' }).click()]);
const icsPath = `${SHOTS}/event.ics`; await dl.saveAs(icsPath);
const ics = fs.readFileSync(icsPath, 'utf8');
if (!ics.includes('BEGIN:VEVENT') || !ics.includes('SUMMARY:E2E weekly sync') || !ics.includes('LOCATION:Halong')) throw new Error('bad ics: ' + ics);
ok('.ics downloaded with correct SUMMARY/LOCATION');
await p.getByRole('button', { name: 'Cancel booking' }).click();
await p.getByLabel('This and all following').check();
await p.screenshot({ path: out('25-cancel-series') });
await p.getByRole('button', { name: 'Yes, cancel' }).click();
await p.getByText('2 bookings cancelled').waitFor();
ok('cancel this-and-following cancelled 2');

// ---------- 4. move a booking to another room via edit
const mv = await (await api.post('/api/v1/bookings', { headers: H(await token('alice@company.local', 'Password123!')), data: {
  roomId: halong.id, title: 'E2E move me', attendees: 3,
  startTime: `${addDays(today, 3)}T14:00:00+07:00`, endTime: `${addDays(today, 3)}T15:00:00+07:00` } })).json();
await p.goto(BASE + '/my');
await p.locator('.booking-row', { hasText: 'E2E move me' }).getByRole('button', { name: 'Manage' }).click();
await p.getByRole('button', { name: 'Edit', exact: true }).click();
dlg = p.locator('dialog[open]');
await dlg.getByLabel('Room', { exact: true }).selectOption(sakura.id);
await dlg.getByLabel('Attendees', { exact: true }).fill('5'); // Sakura has 4 seats
await dlg.locator('button[type=submit]').click();
await dlg.getByText('Sakura seats 4').waitFor();
await dlg.getByLabel('Attendees', { exact: true }).fill('3');
await dlg.locator('button[type=submit]').click();
await p.getByText('Booking updated').waitFor();
await p.waitForTimeout(500);
await p.locator('.booking-row', { hasText: 'E2E move me' }).waitFor();
const moved = sql(`select r.name from bookings b join rooms r on r.id=b.room_id where b.title='E2E move me'`).trim();
if (moved !== 'Sakura') throw new Error('not moved: ' + moved);
ok('booking moved Halong -> Sakura (capacity enforced on the new room)');

// ---------- 5. account page: change password
await p.goto(BASE + '/account');
await p.waitForSelector('h1');
await p.getByLabel('Current password', { exact: true }).fill('wrong-wrong');
await p.getByLabel('New password', { exact: true }).fill('NewPassword123!');
await p.getByLabel('Confirm new password', { exact: true }).fill('NewPassword123!');
await p.getByRole('button', { name: 'Update password' }).click();
await p.getByText('The current password is incorrect.').waitFor();
await p.getByLabel('Current password', { exact: true }).fill('Password123!');
await p.getByRole('button', { name: 'Update password' }).click();
await p.getByText('Password updated').waitFor();
await p.screenshot({ path: out('26-account') });
// restore the demo password so the demo accounts keep working
await p.getByLabel('Current password', { exact: true }).fill('NewPassword123!');
await p.getByLabel('New password', { exact: true }).fill('Password123!');
await p.getByLabel('Confirm new password', { exact: true }).fill('Password123!');
await p.getByRole('button', { name: 'Update password' }).click();
await p.getByText('Password updated').waitFor();
ok('change password (wrong current rejected, success, restored)');
await alice.ctx.close();

// ---------- 6. admin: maintenance block with conflicting booking
const admin = await login('admin@company.local', 'Admin12345!');
p = admin.page;
const BD = addDays(today, 4);
const aliceTok = await token('alice@company.local', 'Password123!');
const victim = await (await api.post('/api/v1/bookings', { headers: H(aliceTok), data: {
  roomId: room('Hue').id, title: 'E2E victim', attendees: 4,
  startTime: `${BD}T10:00:00+07:00`, endTime: `${BD}T11:00:00+07:00` } })).json();
await p.goto(BASE + '/admin/blocks');
await p.waitForSelector('h1');
await p.screenshot({ path: out('27-blocks-empty') });
await p.getByRole('button', { name: 'Block a room' }).click();
dlg = p.locator('dialog[open]');
await dlg.getByLabel('Room', { exact: true }).selectOption(room('Hue').id);
await dlg.getByLabel('Start date', { exact: true }).fill(BD);
await dlg.getByLabel('Start time', { exact: true }).selectOption('09:00');
await dlg.getByLabel('End date', { exact: true }).fill(BD);
await dlg.getByLabel('End time', { exact: true }).selectOption('12:00');
await dlg.getByLabel('Reason', { exact: true }).fill('Block E2E projector repair');
await dlg.getByRole('button', { name: 'Block room', exact: true }).click();
await dlg.getByText(/existing booking\(s\) overlap/).waitFor();
await p.screenshot({ path: out('28-block-conflict') });
await dlg.getByRole('button', { name: 'Cancel them and block the room' }).click();
await p.getByText('Room blocked, 1 booking(s) cancelled').waitFor();
await p.waitForSelector('.table');
await p.screenshot({ path: out('29-blocks-list') });
ok('block with conflict: listed, then cancelled-and-blocked');
const st = sql(`select status || ':' || coalesce(cancel_reason,'') from bookings where title='E2E victim'`).trim();
if (st !== 'CANCELLED:Room unavailable: Block E2E projector repair') throw new Error('victim: ' + st);
ok('victim booking cancelled with reason');

// schedule shows hatched block on that day
await p.goto(BASE + '/');
await p.getByLabel('Date', { exact: true }).fill(BD);
await p.waitForSelector('.tl-booking.block');
await p.screenshot({ path: out('30-schedule-block') });
ok('schedule renders the block');

// regular user cannot book a blocked room
const a2 = await login('alice@company.local', 'Password123!');
await a2.page.goto(BASE + '/');
await a2.page.getByLabel('Date', { exact: true }).fill(BD);
await a2.page.waitForSelector('.tl-booking.block');
await a2.page.locator('.tl-booking.block').first().click();
console.log('alice sees block dialog title:', await a2.page.locator('dialog[open] h2').innerText());
console.log('alice has lift button:', await a2.page.getByRole('button', { name: 'Lift block' }).count());
await a2.ctx.close();

// admin lifts block
await p.locator('.tl-booking.block').first().click();
await p.getByRole('button', { name: 'Lift block' }).click();
await p.getByRole('button', { name: 'Yes, lift it' }).click();
await p.getByText('Block lifted').waitFor();
ok('admin lifted the block');
await admin.ctx.close();

// ---------- 7. Vietnamese walkthrough screenshots
const vi = await login('alice@company.local', 'Password123!');
p = vi.page;
await p.getByRole('button', { name: 'VI', exact: true }).click();
await p.waitForSelector('text=Lịch phòng');
await p.getByLabel('Ngày', { exact: true }).fill(addDays(today, 1));
await p.waitForSelector('.tl-track');
await p.screenshot({ path: out('31-vi-schedule') });
await p.getByRole('button', { name: 'Đặt phòng' }).first().click();
dlg = p.locator('dialog[open]');
await dlg.getByLabel('Lặp lại', { exact: true }).selectOption('WEEKLY');
await p.screenshot({ path: out('32-vi-booking') });
await p.keyboard.press('Escape');
await p.goto(BASE + '/find');
await p.waitForSelector('.room-card');
await p.screenshot({ path: out('33-vi-find') });
await p.goto(BASE + '/my');
await p.waitForSelector('.tabs');
await p.screenshot({ path: out('34-vi-my') });
ok('Vietnamese screens rendered');
await vi.ctx.close();

const m = await login('alice@company.local', 'Password123!', { width: 390, height: 800 });
await m.page.getByRole('button', { name: 'VI', exact: true }).click();
await m.page.waitForSelector('.tl-track');
await m.page.screenshot({ path: out('35-mobile-vi') });
await m.ctx.close();

await browser.close();
sql("delete from bookings where title like 'E2E%' or title like 'Block E2E%'");
console.log(errors.length ? 'CONSOLE ERRORS:\n' + errors.join('\n') : 'No unexpected console errors');
