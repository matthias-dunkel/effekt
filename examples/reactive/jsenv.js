class JSEnv {
    constructor(){
        this.eventQueue = []
    }

    pollEvents(){
        var events = this.eventQueue;
        this.eventQueue = []
        return events
    }

    waitFor(ms) {
        var eventId = "waitForSecs" + Date.now() + ms
        
        setTimeout(() => {
            this.eventQueue = this.eventQueue.concat( {id: eventId, data: undefined, type: "waitForSecs"} )
        }, ms)

        return eventId
    }

    question(text) {
        var readline = require('readline').createInterface({
            input: process.stdin,
            output: process.stdout
        })

        var eventId = "question" + Date.now() + text
        readline.question(text + "\n", answer => {
            this.eventQueue = this.eventQueue.concat({id: eventId, data: answer, type: "question"})
            readline.close()
        })

        return eventId
    }
}


