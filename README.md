# Assistant technicien : appel LLM + Amazon Bedrock Managed Knowledge Base (Java)

Démonstrateur d'un assistant qui répond aux questions d'un technicien de maintenance à partir de la documentation technique des fabricants (notices, codes défauts, procédures), et uniquement à partir d'elle.

C'est le **scénario 1 : appel LLM classique**. Le service Java fait une recherche dans la Knowledge Base, puis un seul appel au modèle. Le scénario 2 (agent sur AgentCore Runtime) est dans le dépôt `agentcore-managed-kb-agent-java`.

![Architecture](docs/scenario-1-llm-managed-kb.png)

## Comment ça marche

1. **Retrieve** sur la Managed Knowledge Base. Si le modèle de l'équipement est connu, la recherche est filtrée sur ce modèle (métadonnée `modele`), plus les documents transverses comme les procédures de sécurité.
2. **Converse** vers le modèle (Claude Haiku 4.5 via le profil d'inférence `eu.`, donc inférence dans les régions AWS en Europe) avec un prompt métier en markdown (`src/main/resources/prompts/system-prompt.md`) et les extraits numérotés.
3. Un **garde-fou d'ancrage** (Amazon Bedrock Guardrails, contrôle de contextual grounding) bloque la réponse si elle n'est pas fondée sur les extraits ou si elle est hors sujet.
4. La réponse revient avec ses sources et la consommation en tokens, pour suivre le coût par question.

Si la recherche ne renvoie aucun extrait, le modèle n'est pas appelé : réponse « information non trouvée », sans coût d'inférence.

### Pourquoi Retrieve puis Converse, et pas un seul appel

Une Knowledge Base managée s'interroge avec l'API `Retrieve` (paramètre `managedSearchConfiguration`) ou avec l'API de récupération agentique. Le paramètre `vectorSearchConfiguration` et l'API `RetrieveAndGenerate` sont ceux des Knowledge Bases vectorielles. D'où les deux appels. C'est aussi ce qui donne la main sur le prompt métier, le garde-fou et le format de réponse. La recherche hybride (mots-clés et sémantique) est activée par le service sans index à configurer.

Le code clé est dans `KnowledgeBaseRetriever` :

```java
ManagedSearchConfiguration.builder()
        .numberOfResults(5)
        .filter(RetrievalFilter.fromOrAll(List.of(equalsModel("Condensa 24"), equalsModel("Tous"))))
        .build();
```

## Prérequis

- Compte AWS, région `eu-west-1`, accès au modèle Claude Haiku 4.5 activé dans Amazon Bedrock
- AWS CLI v2, `jq`
- Java 21 ou plus (testé en 21, compatible 25), Maven 3.9

## Déployer

```bash
./scripts/deploy.sh
```

Le script crée le stack CloudFormation (`infra/template.yaml`), charge `sample-docs/` dans S3, lance l'indexation et écrit la configuration locale dans `.deploy/outputs.sh`.

## Lancer et tester

```bash
source .deploy/outputs.sh
mvn spring-boot:run

./scripts/ask.sh "Que signifie le code F28 ?"
./scripts/ask.sh "Que signifie le code F28 ?" "Condensa 24"
./scripts/ask.sh "Quelle valeur de CO2 à puissance maximale en G20 ?" "Condensa 24"
./scripts/ask.sh "Quel est le couple de serrage des vis de la façade ?" "Condensa 24"
./scripts/ask.sh "Je sens une odeur de gaz en arrivant, que faire ?"
```

Le premier appel montre pourquoi le modèle d'équipement compte : F28 n'a pas la même signification chez les deux fabricants de la documentation d'exemple. Le quatrième appel porte sur une information absente de la documentation : l'assistant doit le dire au lieu d'inventer.

Exemple de réponse :

```json
{
  "answer": "...",
  "status": "ANSWERED",
  "sources": [{ "index": 1, "document": "thermalys-condensa-24-notice-technique.md", "modele": "Condensa 24", "score": 0.61 }],
  "usage": { "modelId": "eu.anthropic.claude-haiku-4-5-20251001-v1:0", "inputTokens": 2150, "outputTokens": 180, "latencyMs": 2400 }
}
```

`status` vaut `ANSWERED`, `NOT_FOUND` (rien dans la documentation, modèle non appelé) ou `BLOCKED` (garde-fou d'ancrage).

## Tests

```bash
mvn test
```

Tests unitaires sans appel AWS : configuration de recherche managée (jamais `vectorSearchConfiguration`), filtre par modèle, pas d'appel au modèle sans extrait, marquage des blocs pour le contrôle d'ancrage, gestion d'une réponse bloquée, validation de l'API.

## Documentation d'exemple

`sample-docs/` contient une documentation **fictive** (fabricants et modèles inventés) : deux chaudières gaz, une pompe à chaleur et une procédure de sécurité. Chaque document a un fichier `.metadata.json` (fabricant, modèle, type d'équipement, type de document) utilisé pour le filtrage. Pour vos notices réelles, déposez les PDF dans le bucket avec le même type de fichier de métadonnées, ou branchez directement SharePoint, Confluence ou Google Drive comme source de la Knowledge Base.

## Choix d'architecture (AWS Well-Architected)

| Pilier | Ce qui est en place |
|---|---|
| Sécurité | Pas de clé statique (chaîne de credentials par défaut, rôle IAM en production). Politique IAM applicative limitée à la Knowledge Base, au profil d'inférence européen et au garde-fou. Bucket chiffré, accès public bloqué, TLS obligatoire. Rôle de la Knowledge Base limité au bucket, avec conditions `aws:SourceAccount` et `aws:SourceArn`. Validation des entrées (1 000 caractères max). Erreurs AWS traduites sans fuite d'information. |
| Fiabilité | Retries standard du SDK, timeouts explicites, réponse HTTP 429 claire en cas de saturation, infrastructure décrite en CloudFormation. |
| Efficacité des performances | Recherche hybride gérée par le service, filtrage par métadonnées pour réduire le bruit, réponse courte pensée pour un écran de téléphone. |
| Optimisation des coûts | Modèle léger par défaut (changeable sans code via `MODEL_ID`), pas d'appel au modèle sans extrait, plafond de tokens, tokens renvoyés à chaque réponse pour mesurer le coût par question. |
| Excellence opérationnelle | Logs structurés par question (statut, extraits, tokens, latence), endpoint de santé, scripts de déploiement et de suppression. |
| Durabilité | Services managés et serverless, pas de capacité réservée inactive. |

## Avant la production

- **Authentification** : l'API n'embarque pas d'authentification. Elle est conçue pour être intégrée au backend existant et exposée derrière son authentification. Ne pas l'exposer telle quelle.
- **Réseau** : ajouter des VPC endpoints (AWS PrivateLink) pour Amazon Bedrock si le backend tourne dans des sous-réseaux privés.
- **Évaluation** : constituer un jeu de questions et réponses attendues sur la vraie documentation, et le rejouer à chaque changement de modèle, de prompt ou de seuil du garde-fou.
- **Seuil du garde-fou** : `GroundingThreshold` (0,7 par défaut) est à calibrer sur ce jeu d'évaluation.

## Supprimer les ressources

```bash
./scripts/destroy.sh
```

## Références

- [Build enterprise search for agents with Amazon Bedrock Managed Knowledge Base](https://aws.amazon.com/blogs/machine-learning/build-enterprise-search-for-agents-with-amazon-bedrock-managed-knowledge-base/)
- [Contextual grounding check (Amazon Bedrock Guardrails)](https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails-contextual-grounding-check.html)
- [Service role for managed Amazon Bedrock Knowledge Bases](https://docs.aws.amazon.com/bedrock/latest/userguide/kb-managed-permissions.html)
- [AWS::Bedrock::KnowledgeBase KnowledgeBaseConfiguration](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-bedrock-knowledgebase-knowledgebaseconfiguration.html)
- [Claude Haiku 4.5 sur Amazon Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-haiku-4-5.html)
