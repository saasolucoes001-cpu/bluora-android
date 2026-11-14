# Migração Bluora autorizada — 14/11/2026

O proprietário autorizou nova assinatura Android geração 3, pacote `br.com.rbwone.web`, versão 1.5.26/código 21, reinstalação com perda dos dados locais e remoção do repositório extra `rbw-one-android`. O único canal ativo de atualização é `https://saasolucoes001-cpu.github.io/bluora-android/web-v2-latest.json`. Preservar a nova chave e o backup protegido documentados no README. A autorização desta migração substitui as exigências históricas conflitantes de preservar a assinatura anterior ou exigir Publicar do portal. Não criar outros repositórios.

# Regra de versão pública única do RBW One

- Android, Windows, Linux e site devem usar a mesma versão pública. A fonte canônica é `release-version.json` no repositório `saasolucoes001-cpu/bluora`.
- Antes de compilar uma distribuição, executar a sincronização e a conferência de fontes daquele repositório com `--android` apontando para esta cópia. O versionCode Android deve crescer e não é o número público exibido.
- Não publicar uma versão isolada de Android que diverge dos demais destinos. Conferir o pacote real, certificado original, números internos, hashes e provas de testes. APK debug não é distribuição de produção.
- Manter `br.com.rbwone.web`, certificado geração 3 e histórico dos canais. Nunca substituir a assinatura por falta da chave privada.
- Ativar o canal Android somente depois do comprovante de autorização emitido pela ação Publicar do preview, vinculado ao APK exato. Concluir a publicação somente após conferir os quatro canais com `check-public-versions.mjs`.
- Se faltar assinatura, compilação ou destino indispensável, manter a publicação bloqueada e relatar a pendência. Alterar fontes ou nomes de arquivos não significa que um instalador novo foi compilado ou publicado.
