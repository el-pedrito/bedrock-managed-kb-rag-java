# Assistant technicien : Spring AI + Amazon Bedrock Managed Knowledge Base (Java)

Démonstrateur d'un assistant qui répond aux questions d'un technicien de maintenance à partir de la documentation technique des fabricants (notices, codes défauts, procédures), et uniquement à partir d'elle.

C'est le **scénario 1 : appel LLM classique**. Le service fait une recherche dans la Knowledge Base, un appel au modèle, puis un contrôle d'ancrage de la réponse. Le scénario 2 (agent sur AgentCore Runtime) est dans le dépôt `agentcore-managed-kb-agent-java` et réutilise la même Knowledge Base.

Socle : **Java 25, Spring Boot 4.1, Spring AI 2.0.1**, Claude Haiku 4.5 (profil d'inférence `eu.`), région `eu-west-1`, infrastructure en **Terraform**.

![Architecture](docs/scenario-1-llm-managed-kb.png)

## Comment ça marche

1. **Recherche** : `VectorStore` Spring AI, implémenté sur la Managed Knowledge Base (`ManagedKnowledgeBaseVectorStore`). Si le modèle de l'équipement est connu, la recherche est filtrée sur ce modèle (métadonnée `modele`), plus les documents transverses comme les procédures de sécurité.
2. **Génération** : `ChatClient` Spring AI sur Bedrock Converse, avec un prompt métier en markdown (`src/main/resources/prompts/system-prompt.md`) et les extraits numérotés.
3. **Contrôle d'ancrage** : `ApplyGuardrail` (Amazon Bedrock Guardrails, contextual grounding) vérifie que la réponse est fondée sur les extraits et pertinente pour la question. Sinon, elle est remplacée par un message neutre. Le contrôle **bloque par défaut** : une évaluation absente ou incomplète (pas de score, texte hors limites, garde-fou mal configuré) bloque la réponse, et l'application refuse de démarrer si `GUARDRAIL_ID` ou `GUARDRAIL_VERSION` manque. Le désactiver demande un choix explicite (`GROUNDING_CHECK=false`).
4. La réponse revient avec ses sources, les scores d'ancrage et la consommation (tokens, unités de garde-fou), pour suivre le coût par question.

Si la recherche ne renvoie aucun extrait, le modèle n'est pas appelé : réponse `NOT_FOUND`, sans coût d'inférence.

## Spring AI ou SDK AWS : l'approche hybride, couche par couche

Le spike Dak Tech a comparé Spring AI et le SDK AWS. Les deux conclusions étaient justes : Spring AI réduit le code et garde le modèle interchangeable, le SDK suit Bedrock sans retard. On prend donc le meilleur des deux à chaque couche.

| Couche | Choix | Pourquoi |
|---|---|---|
| Prompt, appel au modèle | Spring AI `ChatClient` | Moins de code, modèle changeable par configuration |
| Abstraction de recherche | Spring AI `VectorStore`, `SearchRequest`, expressions de filtre | Code applicatif indépendant du moteur de recherche |
| `Retrieve` sur la Knowledge Base managée | SDK AWS (`bedrockagentruntime`), encapsulé dans `ManagedKnowledgeBaseVectorStore` | Le vector store Bedrock Knowledge Base de Spring AI 2.0.1 envoie toujours `vectorSearchConfiguration`, refusé par une Knowledge Base managée |
| Contrôle d'ancrage | SDK AWS (`bedrockruntime` `ApplyGuardrail`) | Spring AI 2.0.1 ne transmet ni `guardrailConfig` ni les blocs `guardContent` à Converse (vérifié dans `BedrockChatOptions` 2.0.1). `ApplyGuardrail` est l'API AWS prévue pour un garde-fou indépendant du modèle |

Règle retenue : **Spring AI là où il fait gagner du temps, le SDK là où Bedrock avance plus vite que Spring AI.** Le jour où Spring AI couvre la Knowledge Base managée ou les garde-fous, on remplace une classe, le reste du code ne bouge pas.

La recherche est appelée explicitement plutôt que via `QuestionAnswerAdvisor`, pour deux raisons : ne pas appeler le modèle quand la documentation ne contient rien, et réutiliser exactement les mêmes extraits comme source de vérité du contrôle d'ancrage.

### Pourquoi deux appels (recherche puis génération), et pas un seul

Une Knowledge Base managée s'interroge avec `Retrieve` (`managedSearchConfiguration`) ou avec la récupération agentique (`AgenticRetrieveStream`). `vectorSearchConfiguration` et `RetrieveAndGenerate` sont ceux des Knowledge Bases vectorielles : c'est l'erreur rencontrée dans le spike. `RetrieveAndGenerate` enchaîne lui aussi une recherche et une génération côté service : passer à deux appels ajoute surtout un aller-retour réseau, négligeable devant la génération. Le volume de tokens dépend du prompt construit (le nôtre ajoute un prompt métier), et le contrôle d'ancrage est facturé à part. En échange, on garde la main sur le prompt métier, le modèle, le garde-fou et le format de réponse.

```java
SearchRequest.builder()
        .query("Que signifie le code F28 ?")
        .topK(5)
        .filterExpression(b.or(b.eq("modele", "Condensa 24"), b.eq("modele", "Tous")).build())
        .build();
// -> Retrieve avec managedSearchConfiguration { numberOfResults: 5, filter: orAll[...] }
```

## Prérequis

- Compte AWS, région `eu-west-1`, accès au modèle Claude Haiku 4.5 activé dans Amazon Bedrock
- Terraform 1.9 ou plus (provider `hashicorp/aws` 6.67 ou plus), AWS CLI v2, `jq`
- Java 25, Maven 3.9

## Déployer

```bash
AWS_PROFILE=<profil> EXPECTED_ACCOUNT_ID=<compte> ./scripts/deploy.sh
```

Le script fait un `terraform apply` dans `infra/` (bucket chiffré, documentation `sample-docs/`, Knowledge Base managée avec son connecteur S3, garde-fou d'ancrage, politique IAM applicative), lance l'indexation et écrit la configuration locale dans `.deploy/outputs.sh`.

| Fichier Terraform | Contenu |
|---|---|
| `storage.tf` | Bucket de documentation (chiffrement, versioning, accès public bloqué, TLS obligatoire) et chargement des notices avec leurs `.metadata.json` |
| `knowledge_base.tf` | Rôle de service, Knowledge Base `type = "MANAGED"`, source de données `MANAGED_KNOWLEDGE_BASE_CONNECTOR` (S3) |
| `guardrail.tf` | Garde-fou contextual grounding (seuils ancrage et pertinence) et sa version publiée |
| `application_iam.tf` | Politique à attacher au rôle du backend : `Retrieve`, profil d'inférence EU, `ApplyGuardrail` |

## Lancer et tester

```bash
source .deploy/outputs.sh
mvn spring-boot:run

./scripts/ask.sh "Que signifie le code défaut F28 ?"
./scripts/ask.sh "Que signifie le code défaut F28 ?" "Condensa 24"
./scripts/ask.sh "Quel est le prix d'une Condensa 24 neuve ?" "Condensa 24"
./scripts/ask.sh "Je sens une odeur de gaz en arrivant, que faire ?"
```

- **F28 sans modèle** : F28 n'a pas la même signification sur les deux chaudières de la documentation. L'assistant donne les deux et demande le modèle.
- **F28 avec le modèle** : réponse unique, sourcée.
- **Odeur de gaz** : la consigne de sécurité passe en premier (règle du prompt métier).
- **Question hors documentation** : `NOT_FOUND`, l'assistant le dit au lieu d'inventer.

Résultats mesurés le 01/10/2026 sur cette documentation (indicatifs) :

| Question | Statut | Ancrage | Pertinence | Tokens entrée / sortie | Latence |
|---|---|---|---|---|---|
| F28 sans modèle | ANSWERED (2 modèles, demande de précision) | 0,91 | 1,0 | 2 205 / 280 | 3,8 s |
| F28 Condensa 24 | ANSWERED | 1,0 | 1,0 | 2 239 / 149 | 2,9 s |
| F28 Ecoline 35 | ANSWERED | 1,0 | 1,0 | 1 243 / 154 | 2,7 s |
| CO2 à puissance max (G20), Condensa 24 | ANSWERED | 0,99 | 1,0 | 1 656 / 60 | 1,9 s |
| Odeur de gaz (consigne de sécurité) | ANSWERED | 0,65 | 0,84 | 1 758 / 177 | 3,2 s |
| Couple de serrage façade (absent de la doc) | NOT_FOUND | 0,94 | 1,0 | 1 287 / 59 | 1,8 s |
| Prix d'une Condensa 24 | NOT_FOUND | 0,98 | 1,0 | 1 651 / 57 | 1,7 s |

Température 0 : sur ces essais, les mêmes questions ont donné les mêmes scores d'un appel à l'autre. C'est une observation, pas une garantie (classement de la recherche et scores du garde-fou peuvent varier).

Forme de la réponse :

```json
{
  "answer": "...",
  "status": "ANSWERED",
  "sources": [{ "index": 1, "document": "thermalys-condensa-24-notice-technique.md", "modele": "Condensa 24", "score": 0.61 }],
  "usage": { "modelId": "eu.anthropic.claude-haiku-4-5-20251001-v1:0", "inputTokens": 2172, "outputTokens": 149, "guardrailUnits": 5, "latencyMs": 3166 },
  "grounding": { "groundingScore": 1.0, "relevanceScore": 1.0 }
}
```

`status` vaut `ANSWERED`, `NOT_FOUND` (rien dans la documentation, ou refus exact du modèle) ou `BLOCKED` (contrôle d'ancrage sous le seuil ou impossible).

## Tests

```bash
mvn test
```

28 tests unitaires sans appel AWS : `Retrieve` toujours en `managedSearchConfiguration` (jamais `vectorSearchConfiguration`), traduction des filtres Spring AI (égalité, `in`, groupes, `&&`, `||`, refus explicite des opérateurs non traduits), seuil de similarité, pas d'appel au modèle sans extrait, qualificatifs du contrôle d'ancrage (`grounding_source`, `query` = la question seule, `guard_content`), blocage quand le garde-fou ne renvoie pas ses deux scores ou que le texte dépasse ses limites, refus de démarrer sans version de garde-fou, balises neutralisées dans les extraits et la question, réponse vide bloquée, refus exact remonté en `NOT_FOUND`, validation de l'API.

## Documentation d'exemple

`sample-docs/` contient une documentation **fictive** (fabricants et modèles inventés) : deux chaudières gaz où F28 n'a pas le même sens, une pompe à chaleur et une procédure de sécurité. Chaque document a un fichier `.metadata.json` (fabricant, modèle, type d'équipement, type de document) utilisé pour le filtrage. Pour de vraies notices, déposer les PDF avec le même type de fichier de métadonnées, ou brancher SharePoint, Confluence ou Google Drive comme source de la Knowledge Base. L'extraction des images (schémas) est activée sur le connecteur.

## Choix d'architecture (AWS Well-Architected)

| Pilier | Ce qui est en place |
|---|---|
| Sécurité | Pas de clé statique (chaîne de credentials par défaut, rôle IAM en production). Politique IAM applicative limitée à la Knowledge Base, au profil d'inférence européen et au garde-fou. Bucket chiffré, accès public bloqué, TLS obligatoire. Rôle de la Knowledge Base limité au bucket, avec conditions `aws:SourceAccount` et `aws:SourceArn`. Validation des entrées (1 000 caractères max, la limite de la query du contrôle d'ancrage). API à l'écoute de `127.0.0.1` par défaut. Extraits et question traités comme des données : chevrons neutralisés et règle explicite dans le prompt contre l'injection d'instructions. |
| Fiabilité | Retries standard du SDK, timeouts explicites, throttling Bedrock et Knowledge Base renvoyé en 429, contrôle d'ancrage en fail-closed, indexation bornée dans le temps au déploiement, infrastructure décrite en Terraform. |
| Efficacité des performances | Recherche hybride gérée par le service, filtrage par métadonnées, réponse courte pensée pour un écran de téléphone. |
| Optimisation des coûts | Modèle léger par défaut (changeable sans code via `MODEL_ID`), pas d'appel au modèle sans extrait, plafond de tokens, tokens et unités de garde-fou renvoyés à chaque réponse. |
| Excellence opérationnelle | Une ligne de log clé=valeur par question (statut, extraits, tokens, scores d'ancrage, latence), endpoint de santé, scripts de déploiement et de suppression, checkov et Semgrep sans finding bloquant. |
| Durabilité | Services managés et serverless, pas de capacité réservée inactive. |

## Avant la production

- **Authentification** : l'API n'embarque pas d'authentification. Elle est conçue pour être intégrée au backend existant et exposée derrière son authentification. Ne pas l'exposer telle quelle (`SERVER_ADDRESS` ne s'ouvre que derrière cette authentification).
- **Rôle IAM** : attacher la politique `application_policy_arn` au rôle du backend (variable Terraform `application_role_name`) et tester avec ce rôle seul, pas avec un profil administrateur.
- **Contrôles non activés pour la démo** (marqués `checkov:skip` avec la raison dans `infra/storage.tf`) : clé KMS gérée par le client sur le bucket, journalisation des accès S3, réplication cross-region, notifications d'événements.
- **État Terraform** : passer sur le backend S3 commenté dans `infra/versions.tf`.
- **Réseau** : ajouter des VPC endpoints (AWS PrivateLink) pour Amazon Bedrock si le backend tourne dans des sous-réseaux privés.
- **Évaluation** : constituer un jeu de 20 à 30 vraies questions de techniciens avec la bonne réponse, et le rejouer à chaque changement de modèle, de prompt ou de seuil.
- **Seuils du garde-fou** : `grounding_threshold` (0,5) et `relevance_threshold` (0,5) sont à calibrer sur ce jeu d'évaluation. Mesuré ici : une réponse fidèle qui reformule une liste de consignes (odeur de gaz) score 0,65 ; à 0,7 elle était bloquée à tort. Un seuil trop haut bloque des bonnes réponses, un seuil trop bas laisse passer des approximations : seul un jeu d'évaluation réel permet de trancher.

## Supprimer les ressources

```bash
AWS_PROFILE=<profil> EXPECTED_ACCOUNT_ID=<compte> ./scripts/destroy.sh
```

Supprimer d'abord le scénario 2 s'il est déployé : il utilise cette Knowledge Base.

## Références

- [Build enterprise search for agents with Amazon Bedrock Managed Knowledge Base](https://aws.amazon.com/blogs/machine-learning/build-enterprise-search-for-agents-with-amazon-bedrock-managed-knowledge-base/)
- [Connecteur Amazon S3 d'une Knowledge Base managée](https://docs.aws.amazon.com/bedrock/latest/userguide/kb-managed-ds-s3.html)
- [Service role for managed Amazon Bedrock Knowledge Bases](https://docs.aws.amazon.com/bedrock/latest/userguide/kb-managed-permissions.html)
- [Contextual grounding check avec ApplyGuardrail](https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails-contextual-grounding-check.html)
- [Terraform `aws_bedrockagent_knowledge_base`](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/bedrockagent_knowledge_base)
