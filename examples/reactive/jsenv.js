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
}


