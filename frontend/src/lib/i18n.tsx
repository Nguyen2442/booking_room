import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import { en, vi, type Key } from './messages';
import { prettyEquipment } from './format';
import { setDateLocale } from './time';

export type Lang = 'en' | 'vi';
const STORAGE_KEY = 'booking.lang';
const dictionaries: Record<Lang, Record<Key, string>> = { en, vi };
const LOCALES: Record<Lang, string> = { en: 'en-GB', vi: 'vi-VN' };

type Vars = Record<string, string | number>;
type PluralBase = Key extends infer K ? (K extends `${infer B}_one` ? B : never) : never;

function readLang(): Lang {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === 'vi' || v === 'en' ? v : 'en'; // English is the default, never guessed from the browser
  } catch {
    return 'en';
  }
}

function interpolate(text: string, vars?: Vars) {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
}

interface I18n {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (key: Key, vars?: Vars) => string;
  /** plural-aware: picks `${base}_one` or `${base}_other` */
  tn: (base: PluralBase, n: number, vars?: Vars) => string;
  /** "2h 30m" / "2 giờ 30 phút" */
  dur: (minutes: number) => string;
  /** Localised equipment tag, falling back to a prettified code for custom equipment. */
  eq: (code: string) => string;
  /** Human message for any error; prefers a translation keyed by the server's error code. */
  err: (e: unknown) => string;
}

const Ctx = createContext<I18n | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(readLang);

  useEffect(() => {
    document.documentElement.lang = lang;
    setDateLocale(LOCALES[lang]);
  }, [lang]);
  setDateLocale(LOCALES[lang]); // also synchronously, so the very first render formats dates correctly

  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    try {
      localStorage.setItem(STORAGE_KEY, l);
    } catch {
      /* ignore */
    }
  }, []);

  const value = useMemo<I18n>(() => {
    const dict = dictionaries[lang];
    const t = (key: Key, vars?: Vars) => interpolate(dict[key] ?? en[key] ?? key, vars);
    return {
      lang,
      setLang,
      t,
      tn: (base, n, vars) => t(`${base}_${n === 1 ? 'one' : 'other'}` as Key, { n, ...vars }),
      dur: (minutes) => {
        const h = Math.floor(minutes / 60);
        const m = minutes % 60;
        const parts = [];
        if (h) parts.push(t('dur.h', { n: h }));
        if (m || !h) parts.push(t('dur.m', { n: m }));
        return parts.join(' ');
      },
      eq: (code) => {
        const k = `eq.${code}` as Key;
        return k in en ? t(k) : prettyEquipment(code);
      },
      err: (e) => {
        if (!(e instanceof ApiError)) return t('common.unknownError');
        const k = `err.${e.code}` as Key;
        const details: Vars = {};
        for (const [key, v] of Object.entries(e.problem)) if (typeof v === 'string' || typeof v === 'number') details[key] = v;
        const base = k in en ? t(k, details) : e.message;
        // series errors arrive as "Occurrence on 2030-01-08: <reason>"; keep the date, localise the reason
        const m = /^Occurrence on (\d{4}-\d{2}-\d{2}): /.exec(e.message);
        return m ? t('err.occurrence', { date: m[1], message: base }) : base;
      },
    };
  }, [lang, setLang]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useI18n() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useI18n outside I18nProvider');
  return v;
}

export function LanguageSwitch() {
  const { lang, setLang, t } = useI18n();
  return (
    <div className="lang" role="group" aria-label={t('lang.label')}>
      {(['en', 'vi'] as Lang[]).map((l) => (
        <button key={l} type="button" lang={l} aria-pressed={lang === l} className={lang === l ? 'active' : ''} onClick={() => setLang(l)}>
          {l === 'en' ? 'EN' : 'VI'}
        </button>
      ))}
    </div>
  );
}
