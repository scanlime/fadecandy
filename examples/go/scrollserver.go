package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"github.com/kellydunn/go-opc"
	"io/ioutil"
	"log"
	"math/rand"
	"net/http"
	"time"
)

type Color struct {
	R uint8 `json:"r"`
	G uint8 `json:"g"`
	B uint8 `json:"b"`
}

type Scroller struct {
	Delay    int   `json:"delay"`
	TrainLen int   `json:"train_len"`
	Random   bool  `json:"random"`
	Color    Color `json:"color"`
}

var homeChan chan Scroller

func Random(min, max int) uint8 {
	xr := rand.Intn(max-min) + min
	return uint8(xr)
}

func main() {
	rand.Seed(time.Now().Unix())

	serverPtr := flag.String("fcserver", "localhost:7890", "Fadecandy server and port to connect to")
	listenPortPtr := flag.Int("port", 8080, "Port to serve UI from")
	ledsLen := flag.Int("leds", 64, "Number of LEDs in the string")
	flag.Parse()

	homeChan = make(chan Scroller, 1)

	go func() { LEDSender(homeChan, *serverPtr, *ledsLen) }()

	fs := http.FileServer(http.Dir("./static"))
	http.Handle("/static/", http.StripPrefix("/static/", fs))
	http.Handle("/", http.StripPrefix("/", fs))
	http.HandleFunc("/update", UpdateHandler)

	log.Println("Listening on", fmt.Sprintf("http://0.0.0.0:%d", *listenPortPtr), "...")
	log.Fatal(http.ListenAndServe(fmt.Sprintf(":%d", *listenPortPtr), nil))
}

func UpdateHandler(w http.ResponseWriter, r *http.Request) {
	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		log.Fatal("Could not read request body", err)
	}
	var inscroll Scroller
	err = json.Unmarshal(body, &inscroll)
	if err != nil {
		log.Fatal("Could not unmarshal request body", err)
	}

	ss := inscroll

	//send on the home channel, nonblocking
	select {
	case homeChan <- ss:
	default:
		log.Println("msg NOT sent")
	}

	fmt.Fprintf(w, "HomeHandler %d", ss.Delay)
}

func LEDSender(c chan Scroller, server string, ledsLen int) {

	props := Scroller{ledsLen, 7, false, Color{255, 0, 0}}
	props.Delay = 100

	// Create a client
	oc := opc.NewClient()
	err := oc.Connect("tcp", server)
	if err != nil {
		log.Fatal("Could not connect to Fadecandy server", err)
	}

	for {
		for i := 0; i < ledsLen; i++ {
			// send pixel data
			m := opc.NewMessage(0)
			m.SetLength(uint16(ledsLen * 3))

			for ii := 0; ii < props.TrainLen; ii++ {
				pix := i + ii
				if pix >= ledsLen {
					pix = props.TrainLen - ii - 1
				}
				if props.Random {
					m.SetPixelColor(pix, Random(2, 255), Random(2, 255), Random(2, 255))
				} else {
					m.SetPixelColor(pix, props.Color.R, props.Color.G, props.Color.B)
				}
			}

			err := oc.Send(m)
			if err != nil {
				log.Println("couldn't send Color", err)
			}
			time.Sleep(time.Duration(props.Delay) * time.Millisecond)

			// receive from channel
			select {
			case props = <-c:
			default:
			}
		}
	}
}
