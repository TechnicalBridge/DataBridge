/**
 * El puente colgante de DataBridge (#274): dos torres, el cable y la calzada, con
 * el sol terracota. En SVG y con los colores del tema, asi es la misma marca en
 * claro y en oscuro. El favicon (public/favicon.svg) es el mismo dibujo.
 */
export default function Logo({ size = 44 }) {
  return (
    <svg className="brand-mark" width={size} height={size} viewBox="0 0 48 48" role="img"
         aria-label="DataBridge">
      <rect width="48" height="48" rx="12" fill="var(--brand)" />
      {/* Las dos torres, el cable que cuelga entre ellas y baja a cada orilla, y la calzada. */}
      <path d="M15.5 12V37M32.5 12V37" stroke="var(--on-brand)" strokeWidth="3.6" strokeLinecap="round" />
      <path d="M15.5 13.5Q24 29 32.5 13.5M15.5 13.5Q11 24 6 29.5M32.5 13.5Q37 24 42 29.5" fill="none"
            stroke="var(--on-brand)" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M6 30H42" stroke="var(--on-brand)" strokeWidth="3.6" strokeLinecap="round" />
      <circle className="brand-sol" cx="24" cy="12" r="3.2" fill="var(--accent)" />
    </svg>
  );
}
