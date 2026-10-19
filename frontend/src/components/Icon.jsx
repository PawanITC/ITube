const paths = {
  menu: <><path d="M4 6h16M4 12h16M4 18h16" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></>,
  mic: <><rect x="9" y="3" width="6" height="12" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3m-4 0h8" /></>,
  userPlus: <><circle cx="9" cy="8" r="4" /><path d="M2 21v-1a7 7 0 0 1 14 0v1m5-12v6m-3-3h6" /></>,
  home: <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-6v-7h-4v7H4a1 1 0 0 1-1-1z" /></>,
  shorts: <><path d="M14.5 3.5 6 8.4a4 4 0 0 0 0 7.2l8.5 4.9a4 4 0 0 0 4-6.9L16 12l2.5-1.6a4 4 0 0 0-4-6.9Z" /><path d="m10 9 5 3-5 3z" /></>,
  subscriptions: <><rect x="3" y="5" width="18" height="14" rx="3" /><path d="m10 9 5 3-5 3z" /></>,
  history: <><path d="M3 12a9 9 0 1 0 2.6-6.4L3 8" /><path d="M3 3v5h5m4-1v5l3 2" /></>,
  playlist: <><path d="M4 6h12M4 11h12M4 16h7m5-2 5 3-5 3z" /></>,
  later: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  liked: <><path d="M7 10v11H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zm0 0 4-8c2 0 3 1 3 3v3h5a2 2 0 0 1 2 2l-2 9a2 2 0 0 1-2 2H7" /></>,
  more: <><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></>,
  create: <><path d="M12 5v14m-7-7h14" /></>,
  bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9m-8 12h4" /></>,
  play: <><path d="m9 6 10 6-10 6z" /></>,
};

export default function Icon({ name, size = 24, ...props }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name]}</svg>;
}
