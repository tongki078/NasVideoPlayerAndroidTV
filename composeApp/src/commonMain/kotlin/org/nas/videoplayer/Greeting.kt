package org.nas.videoplayer

class Greeting {
    private val platform = getPlatform()

    fun greet(): String {
        return "Hello, ${platform.name}!"
    }
}

/*
*
* echo "# NasVideoPlayer" >> README.md
git init
git add README.md
git commit -m "first commit"
git branch -M main
git remote add origin git@github.com:tongki078/NasVideoPlayer.git
git push -u origin main
* */