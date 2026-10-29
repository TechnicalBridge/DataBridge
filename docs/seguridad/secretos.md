# Que ninguna clave llegue al repositorio

En octubre quedaron públicos un token de Mercado Pago y una clave de Render
([#245](https://github.com/TechnicalBridge/DataBridge/issues/245)): llegaron al historial de `main` sin que nada lo
frenara. El repositorio es público, así que **una clave que se sube es una clave filtrada**, aunque después se borre
el commit. Desde [#232](https://github.com/TechnicalBridge/DataBridge/issues/232) hay tres barreras:

| Barrera | Dónde | Qué revisa |
| --- | --- | --- |
| **El bloqueo de GitHub** | Al hacer `push` | Los patrones de los proveedores que GitHub conoce. Si encuentra uno, rechaza el push |
| **El check *Secretos*** | En cada PR y en cada push a `main` ([`secretos.yml`](../../.github/workflows/secretos.yml)) | Los commits nuevos, con gitleaks y las reglas de DataBridge ([`.gitleaks.toml`](../../.gitleaks.toml)) |
| **El hook local**, opcional | Antes de cada commit, en tu equipo ([`.githooks/pre-commit`](../../.githooks/pre-commit)) | Lo que vas a commitear, con las mismas reglas |

Ninguna muestra el valor que encuentra: solo la regla, el archivo y la línea.

El historial **no se revisa entero**: trae las claves de #245, que ya se revocaron, y haría fallar todo para siempre.
Por eso el check mira solo lo nuevo. Cada merge se revisa contra la línea propia de la rama (`-m --first-parent`):
sin eso, `git log -p` no muestra el diff de un merge, y una clave escrita al resolver un conflicto pasaría sin que
nadie la viera. Por lo mismo, al traer `main` a tu rama también se revisa lo que llega con él.

## Qué detecta

Las reglas de gitleaks (llaves privadas, tokens de GitHub, de AWS, de Slack, y muchos más), y además:

| Regla | Qué es |
| --- | --- |
| `databridge-mercadopago-token` | Un access token de Mercado Pago. La Public Key no es secreta y no salta |
| `databridge-render-key` | Una API key de Render (`rnd_…`) |
| `databridge-variable-con-valor` | Una variable secreta de DataBridge escrita con su valor, en un `.env`, un script, YAML o la documentación: `CIFRADO_LLAVE`, `INTERNAL_KEY`, `WEBHOOK_SECRET`, `RABBIT_PASSWORD`, las de las tres bases (`DB_*_PASSWORD`), las del correo, las pasarelas, el Banco Central y el asistente. También `MYSQL_*` y `JWT_SECRET`, que ya no se usan pero un `.env` viejo trae |
| `databridge-secreto-por-omision` | Un valor por omisión para una de esas variables, en el compose (`${VARIABLE:-…}`) o en las propiedades (`${VARIABLE:…}`) |

**Lo que deja pasar**, a propósito:
- las variables vacías, como en `.env.example`;
- las referencias obligatorias del compose (`${VARIABLE:?Falta…}`);
- dos valores públicos exactos, listados en `.gitleaks.toml`: la llave de integración que publica Transbank, y las
  huellas SHA-256 con que `preparar-env.ps1` reconoce los valores de desarrollo de antes (de una huella no se saca
  el valor).

Además, `.gitignore` deja fuera cualquier `.env.*` (un `.env.local`, un `.env.produccion`), salvo `.env.example`.

## Activar el hook en tu equipo

Una sola vez por clon, en PowerShell y desde la carpeta del repositorio:

```powershell
winget install --id Gitleaks.Gitleaks --version 8.30.1
git config core.hooksPath .githooks
```

Desde ahí, cada `git commit` revisa lo que vas a commitear. Si encuentra algo, el commit no se hace. Sin gitleaks o
sin Node, el hook avisa y deja pasar: el check del PR revisa igual.

## Si el check o el hook encuentran algo

1. **Si es una clave de verdad, revócala primero en el proveedor** (Mercado Pago, Khipu, Brevo, Render…), aunque
   el PR no se haya mergeado: si ya hiciste push, es pública. Borrarla del commit no la des-publica.
2. Pon la clave nueva donde corresponde: tu `.env` (que no se sube) o, para el servidor, GitHub Secrets.
3. Sácala de tus commits: en la rama de tu PR se puede reescribir (`git commit --amend` o un rebase) y subir con
   `git push --force-with-lease`. **`main` no se reescribe**: si ya llegó ahí, revocarla es lo que la arregla.
4. **Si es un falso positivo** (un valor público, o una prueba): en una prueba, arma la clave ficticia al azar en
   el momento, como `pruebas/secretos.test.cjs`, o usa un valor que se vea falso. Si de verdad es un valor público,
   se agrega su valor exacto a la lista de `.gitleaks.toml`, en un PR con revisión. Los comentarios `gitleaks:allow`
   no sirven: el verificador los ignora a propósito.

Dos pruebas de ms-payments (`SeguridadDePasarelasTest` y `PaymentServiceTest`) tienen tokens ficticios de Webpay que
gitleaks reconoce como claves genéricas. No molestan, porque el check mira solo lo nuevo; si alguien edita esas
líneas, el check las va a marcar, y conviene cambiarlas por un valor armado al azar.

## Correrlo a mano

```powershell
node scripts\verificar-secretos.cjs --staged
node scripts\verificar-secretos.cjs --base (git merge-base origin/main HEAD)
```

El primero revisa lo que está en el índice; el segundo, tus commits desde que la rama salió de `main`. Sale con 0
si no hay nada, con 1 si encontró algo y con 2 si no pudo correr gitleaks. Las reglas se prueban con
`node --test pruebas\secretos.test.cjs` (con `$env:GITLEAKS` apuntando al binario, si no está en el `PATH`).
