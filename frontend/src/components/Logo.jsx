/**
 * El puente. En SVG y con los colores del tema: el PNG de antes estaba hecho
 * para el fondo azul y sobre crema no se leia.
 */
export default function Logo({ size = 44 }) {
  return (
    <svg className="brand-mark" width={size} height={size} viewBox="0 0 48 48" role="img"
         aria-label="DataBridge">
      <rect x="1" y="1" width="46" height="46" rx="14" fill="var(--brand)" />
      <path d="M8 31C16 15 32 15 40 31" fill="none" stroke="var(--on-brand)" strokeWidth="2.6"
            strokeLinecap="round" />
      <path d="M6 33H42" stroke="var(--on-brand)" strokeWidth="2.6" strokeLinecap="round" />
      <path d="M16 22.5V33M24 19V33M32 22.5V33" stroke="var(--on-brand)" strokeWidth="1.8" strokeLinecap="round"
            opacity=".8" />
      <circle className="brand-sol" cx="37" cy="12.5" r="3" fill="var(--accent)" />
    </svg>
  );
}
