const EQUIPO = Object.freeze(["pedcamp", "es-main77", "martinkuruzg-hue", "4mnesia"]);
const excluido = (i) => i.pull_request || /^(decisi[oó]n|retro|[eé]pica)\s*[:(]/iu.test(i.title);

function planificar(issues) {
  let turno = 0;
  const plan = [];
  const ordenados = [...issues].filter(i => !i.pull_request).sort((a, b) =>
    a.created_at.localeCompare(b.created_at) || a.number - b.number);
  for (const issue of ordenados) {
    const asignados = issue.assignees || [];
    if (asignados.length) {
      const indice = asignados.length === 1 ? EQUIPO.indexOf(asignados[0].login) : -1;
      if (indice >= 0) turno = (indice + 1) % EQUIPO.length;
      continue;
    }
    if (excluido(issue)) continue;
    const responsable = EQUIPO[turno];
    turno = (turno + 1) % EQUIPO.length;
    if (issue.state === "open") plan.push({ numero: issue.number, responsable });
  }
  return plan;
}

async function reconciliar(api) {
  const issues = [];
  for (let pagina = 1; ; pagina++) {
    const lote = await api.listar(pagina);
    issues.push(...lote);
    if (lote.length < 100) break;
  }
  for (const paso of planificar(issues)) {
    // Una asignación manual distinta cambia el turno: no continuar con un plan obsoleto.
    const actual = await api.obtener(paso.numero);
    if (actual.assignees.length) {
      if (actual.assignees.length !== 1 || actual.assignees[0].login !== paso.responsable) {
        throw new Error(`Asignación modificada en #${paso.numero}; recalcular en la próxima pasada`);
      }
      continue;
    }
    if (actual.state !== "open" || excluido(actual)) continue;
    const respuesta = await api.asignar(paso.numero, paso.responsable);
    if (!respuesta.assignees.some(a => a.login === paso.responsable)) {
      throw new Error(`GitHub no confirmó asignación de #${paso.numero}`);
    }
  }
}

async function ejecutar() {
  const repo = process.env.GH_REPO;
  if (!/^[\w.-]+\/[\w.-]+$/.test(repo || "") || !process.env.GH_TOKEN) throw new Error("Falta configuración de GitHub");
  async function peticion(ruta, body) {
    const respuesta = await fetch(`https://api.github.com/repos/${repo}/${ruta}`, {
      method: body ? "POST" : "GET",
      headers: { Authorization: `Bearer ${process.env.GH_TOKEN}`, Accept: "application/vnd.github+json",
        "Content-Type": "application/json", "X-GitHub-Api-Version": "2022-11-28" },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(30000),
    });
    if (!respuesta.ok) throw new Error(`GitHub respondió ${respuesta.status}`);
    return respuesta.json();
  }
  await reconciliar({
    listar: p => peticion(`issues?state=all&sort=created&direction=asc&per_page=100&page=${p}`),
    obtener: n => peticion(`issues/${n}`),
    asignar: (n, login) => peticion(`issues/${n}/assignees`, { assignees: [login] }),
  });
}

module.exports = { EQUIPO, planificar, reconciliar };
if (require.main === module) ejecutar().catch(e => { console.error(e.message); process.exitCode = 1; });
