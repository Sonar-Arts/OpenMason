# OpenMason

Open Mason is a game-agnostic engine and toolset, developed in tandem with [Stonebreak](https://github.com/Sonar-Arts/Stonebreak). Our intention is to make it close to what the Aurora engine toolset does for Neverwinter Nights, so that players and developers alike can have access to modding and asset creation tools. Games plug into the editor through plugins (Stonebreak's lives in the Stonebreak repository).

<img width="2498" height="1088" alt="image" src="https://github.com/user-attachments/assets/5c02327e-790f-4bef-9761-4c19ea3f299d" />

## Running Open Mason

Currently, no builds of Open Mason are available due to how early on the project we are. However, the easiest and currently recommended way to run it is by following the below instructions:

1. Download Intellij IDEA Community Edition here: [IntelliJ IDEA Download Page](https://www.jetbrains.com/idea/download/?section=windows)
2. Clone the repo through [git](https://docs.github.com/en/repositories/creating-and-managing-repositories/cloning-a-repository) or IDEA's git [integration](https://www.jetbrains.com/help/idea/using-git-integration.html)
3. Build the native library with `openmason-engine/cenda/setup-cenda.sh`, then build using Maven and run through the IDE.

Engine artifacts are published to [GitHub Packages](https://github.com/orgs/Sonar-Arts/packages?repo_name=OpenMason).
