import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { execSync } from 'node:child_process';
const BASE = process.env.BASE_URL ?? 'http://localhost:8088';
const SHOTS = process.argv[2] ?? 'e2e/screenshots';
mkdirSync(SHOTS, { recursive: true });
const out = (n) => `${SHOTS}/${n}.png`;
const sql = (q) => execSync(`docker compose exec -T db psql -U booking -d booking -qtAc "${q}"`).toString();
sql("delete from bookings where title like 'E2E%'");
sql("delete from rooms where name = 'E2E Room' and not exists (select 1 from bookings b where b.room_id = rooms.id)");
const errors = [];
const browser = await chromium.launch();

async function session(email, pw, viewport = { width: 1360, height: 860 }) {
  const ctx = await browser.newContext({ viewport });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error' && !/409/.test(m.text())) errors.push(`${email} console: ${m.text()}`); });
  page.on('pageerror', (e) => errors.push(`${email} pageerror: ${e.message}`));
  await page.goto(BASE + '/');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(pw);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForSelector('text=Schedule');
  return { ctx, page };
}

// --- login screen + wrong password
{
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 860 } });
  const page = await ctx.newPage();
  await page.goto(BASE + '/');
  await page.screenshot({ path: out('01-login') });
  await page.getByLabel('Email', { exact: true }).fill('alice@company.local');
  await page.getByLabel('Password', { exact: true }).fill('wrong-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForSelector('text=Incorrect email or password');
  console.log('OK wrong password message');
  await ctx.close();
}

// --- alice: schedule, create booking by clicking the grid
const alice = await session('alice@company.local', 'Password123!');
let p = alice.page;
await p.waitForSelector('.tl-track');
await p.screenshot({ path: out('02-schedule') });
console.log('OK schedule rows:', await p.locator('.tl-row').count());

// go to tomorrow so we are not blocked by "past" and demo data is visible
await p.getByLabel('Next day', { exact: true }).click();
await p.waitForTimeout(600);
await p.screenshot({ path: out('03-schedule-tomorrow') });

// click empty area of "Halong" row (4th) around 15:00
const track = p.locator('.tl-track').nth(3);
const box = await track.boundingBox();
const hw = (await p.locator('.tl-hour').first().boundingBox()).width;
await p.mouse.click(box.x + (7 * hw) + 5, box.y + box.height / 2); // 08:00 + 7h = 15:00
await p.waitForSelector('dialog[open]');
await p.getByLabel('Title', { exact: true }).fill('E2E UX test meeting');
await p.getByLabel('Attendees', { exact: true }).fill('2');
await p.screenshot({ path: out('04-booking-dialog') });
await p.getByRole('button', { name: 'Book room' }).click();
await p.waitForSelector('text=Room booked');
console.log('OK booking created via grid click');
await p.waitForTimeout(500);
await p.screenshot({ path: out('05-after-booking') });

// click our booking -> details
await p.locator('.tl-booking.mine', { hasText: 'E2E UX test meeting' }).click();
await p.waitForSelector('text=Booking details');
await p.screenshot({ path: out('06-detail') });
await p.keyboard.press('Escape');

// --- bob tries the same slot => conflict UX with suggestions
const bob = await session('bob@company.local', 'Password123!');
const b = bob.page;
await b.getByLabel('Next day', { exact: true }).click();
await b.waitForTimeout(500);
await b.getByRole('button', { name: 'New booking' }).click();
await b.waitForSelector('dialog[open]');
await b.getByLabel('Title', { exact: true }).fill('E2E Clash');
await b.getByLabel('Attendees', { exact: true }).fill('2');
const roomSel = b.locator('dialog').getByLabel('Room', { exact: true });
await roomSel.selectOption(await roomSel.locator('option', { hasText: 'Halong' }).getAttribute('value'));
await b.locator('dialog').getByLabel('Starts', { exact: true }).selectOption('15:00');
await b.waitForTimeout(700);
await b.screenshot({ path: out('07-busy-warning') });
console.log('OK busy warning shown:', await b.locator('text=is busy at that time').count() > 0);
await b.getByRole('button', { name: 'Book room' }).click();
await b.waitForSelector('text=Someone just booked');
await b.waitForSelector('text=Free at these times');
await b.screenshot({ path: out('08-conflict-suggestions') });
console.log('OK conflict + suggestions');
await bob.ctx.close();

// --- find a room
await p.goto(BASE + '/find');
await p.waitForSelector('text=Find a room');
await p.waitForTimeout(800);
await p.screenshot({ path: out('09-find') });
console.log('OK find page cards:', await p.locator('.room-card').count());

// --- my bookings
await p.goto(BASE + '/my');
await p.waitForSelector('.booking-row');
await p.screenshot({ path: out('10-my') });
// cancel it
await p.getByRole('button', { name: 'Manage' }).first().click();
await p.getByRole('button', { name: 'Cancel booking' }).click();
await p.getByLabel('Reason (optional)', { exact: true }).fill('changed plans');
await p.screenshot({ path: out('11-cancel-confirm') });
await p.getByRole('button', { name: 'Yes, cancel' }).click();
await p.waitForSelector('text=Booking cancelled');
console.log('OK cancelled');

// --- regular user cannot see admin nav / is redirected
console.log('OK user has no admin nav:', (await p.locator('a[href="/admin/rooms"]').count()) === 0);
await p.goto(BASE + '/admin/users');
await p.waitForTimeout(500);
console.log('OK admin route redirects user ->', new URL(p.url()).pathname);
await alice.ctx.close();

// --- admin
const admin = await session('admin@company.local', 'Admin12345!');
const a = admin.page;
await a.goto(BASE + '/admin/rooms');
await a.waitForSelector('.table');
await a.screenshot({ path: out('12-admin-rooms') });
await a.getByRole('button', { name: 'Add room' }).click();
await a.getByLabel('Name', { exact: true }).fill('E2E Room');
await a.getByLabel('Seats', { exact: true }).fill('7');
await a.getByRole('button', { name: 'projector' }).click();
await a.screenshot({ path: out('13-room-form') });
await a.getByRole('button', { name: 'Create room' }).click();
await a.waitForSelector('text=Room created');
console.log('OK room created');
await a.goto(BASE + '/admin/users');
await a.waitForSelector('.table');
await a.screenshot({ path: out('14-admin-users') });
await a.goto(BASE + '/admin/bookings');
await a.waitForSelector('.table');
await a.screenshot({ path: out('15-admin-bookings') });
await admin.ctx.close();

// --- mobile
const m = await session('alice@company.local', 'Password123!', { width: 390, height: 800 });
await m.page.waitForSelector('.tl-track');
await m.page.screenshot({ path: out('16-mobile-schedule') });
await m.page.goto(BASE + '/my');
await m.page.waitForTimeout(600);
await m.page.screenshot({ path: out('17-mobile-my') });
await m.ctx.close();

await browser.close();
sql("delete from bookings where title like 'E2E%'");
sql("delete from rooms where name = 'E2E Room'");
console.log(errors.length ? 'CONSOLE/PAGE ERRORS:\n' + errors.join('\n') : 'No console errors');
