var request = require('request')

function main(params){
  console.log("testing async-noop.js");
  return new Promise(function(resolve, reject) {
    setTimeout(function() {
      resolve({ done: true });
    }, 175);
  })
}
