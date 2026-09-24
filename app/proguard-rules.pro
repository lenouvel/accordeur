# Règles R8 spécifiques à l'app (build release).
# Aucune réflexion dans le code de l'app : les bibliothèques AndroidX, Compose,
# DataStore et coroutines fournissent leurs propres règles (consumer rules).

# Conserver les numéros de ligne pour des stack traces lisibles.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
