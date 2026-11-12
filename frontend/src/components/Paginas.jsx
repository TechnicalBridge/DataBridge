/**
 * Una lista larga, de a pocas: Anteriores, cuantas se ven de cuantas, y
 * Siguientes (#280, #299). Con una sola pagina no se muestra.
 */
export default function Paginas({ total, porPagina, pagina, alCambiar, etiqueta }) {
  if (total <= porPagina) return null;
  const ultima = Math.ceil(total / porPagina) - 1;
  return (
    <nav className="paginas" aria-label={etiqueta}>
      <button type="button" className="btn btn-ghost btn-sm" disabled={pagina === 0}
              onClick={() => alCambiar(pagina - 1)}>
        Anteriores
      </button>
      <span className="hint" aria-live="polite">
        {pagina * porPagina + 1}–{Math.min((pagina + 1) * porPagina, total)} de {total}
      </span>
      <button type="button" className="btn btn-ghost btn-sm" disabled={pagina >= ultima}
              onClick={() => alCambiar(pagina + 1)}>
        Siguientes
      </button>
    </nav>
  );
}

/** Lo que toca mostrar: la pagina pedida, o la ultima que existe si la lista se achico al filtrar. */
export function dePagina(lista, pagina, porPagina) {
  const ultima = Math.max(0, Math.ceil(lista.length / porPagina) - 1);
  const enPagina = Math.min(pagina, ultima);
  return { enPagina, filas: lista.slice(enPagina * porPagina, (enPagina + 1) * porPagina) };
}
