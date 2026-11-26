/**
 * El puente colgante del logo, de borde a borde: el mundo de la portada (#536) y de la entrada (#539).
 */
const TIRANTES = [480, 540, 600, 660, 720, 780, 840, 900, 960];
/** Donde cae el cable principal sobre cada tirante: la curva Q del vano, 38 + 384 t (1 - t). */
const alturaDelCable = (x) => { const t = (x - 420) / 600; return 38 + 384 * t * (1 - t); };

/**
 * El puente colgante del logo, de borde a borde. Se dibuja al cargar: primero el tablero, despues las
 * torres, los cables y los tirantes, y al final sale el sol. Es decorativo: lo que dice esta en el texto.
 */
export default function Puente({ className = "puente" }) {
  return (
    <svg className={className} viewBox="0 0 1440 300" preserveAspectRatio="xMidYMax slice" aria-hidden="true" focusable="false">
      <rect className="puente-agua" x="0" y="282" width="1440" height="18" />
      <path className="puente-trazo puente-tablero" pathLength="1" d="M0 270H1440" />
      <path className="puente-trazo puente-torre" pathLength="1" d="M420 292V30" />
      <path className="puente-trazo puente-torre" pathLength="1" d="M1020 292V30" style={{ "--d": "0.12s" }} />
      <path className="puente-trazo puente-cable" pathLength="1" d="M420 38Q720 230 1020 38" />
      <path className="puente-trazo puente-cable" pathLength="1" d="M420 38Q300 180 40 266" style={{ "--d": "0.08s" }} />
      <path className="puente-trazo puente-cable" pathLength="1" d="M1020 38Q1140 180 1400 266" style={{ "--d": "0.08s" }} />
      {TIRANTES.map((x, i) => (
        <path key={x} className="puente-trazo puente-tirante" pathLength="1"
              d={`M${x} ${alturaDelCable(x).toFixed(1)}V270`} style={{ "--d": `${i * 0.04}s` }} />
      ))}
      <circle className="puente-sol" cx="720" cy="34" r="17" />
    </svg>
  );
}

